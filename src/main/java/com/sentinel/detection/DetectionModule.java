package com.sentinel.detection;

import com.sentinel.diagnosis.DiagnosisModule;
import com.sentinel.diagnosis.DiagnosisModuleLike;
import com.sentinel.diagnosis.DiagnosisResult;
import io.fabric8.kubernetes.api.model.ContainerState;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodCondition;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.WatcherException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Sentinel - Kubernetes Pod Failure Detection Module (Step 1).
 *
 * <p>Event-driven watcher that detects pod failures across all namespaces, deduplicates
 * diagnoses, filters transient/self-recovering failures, and safely extracts pod metadata
 * before handing off to Step 2 (diagnosis).
 */
public final class DetectionModule {

    private DetectionModule() {}

    // ----------------------------------------------------------------------
    // Configuration / tunable constants
    //
    // These are plain (non-final) static fields rather than constants so tests can shrink the
    // timing knobs for fast runs, mirroring the reassignable module attributes the Python/TS
    // versions of this project used for the same purpose.
    // ----------------------------------------------------------------------

    public static Set<String> EXCLUDED_NAMESPACES =
        Set.of("kube-system", "kube-node-lease", "kube-public");

    // Image-pull and container-config errors surface as container *reasons*
    // (state.waiting.reason), not pod phases, so they belong here alongside CrashLoopBackOff.
    public static Set<String> FAILURE_REASONS = Set.of(
        "CrashLoopBackOff", "Error",
        "ImagePullBackOff", "ImageInspectError",
        "CreateContainerConfigError", "ErrImagePull");
    public static Set<String> FAILURE_PHASES = Set.of("Failed", "Pending");

    public static double DEDUP_WINDOW_SECONDS = 5 * 60; // don't re-diagnose within 5 min
    public static double TRANSIENT_WAIT_SECONDS = 30; // must be failing continuously for 30s
    public static double WAITING_STALE_TIMEOUT_SECONDS = 60; // forget "waiting" pods after 1 min
    public static double PENDING_MIN_AGE_SECONDS = 30; // ignore brand-new Pending pods
    public static int MIN_RESTART_COUNT_CRASHLOOP = 1; // diagnose CrashLoopBackOff after 1+ restarts
    public static int CLEANUP_EVERY_N_EVENTS = 50; // sweep stale entries every N events
    public static int RECONNECT_DELAY_SECONDS = 5;

    // ----------------------------------------------------------------------
    // In-memory state
    // ----------------------------------------------------------------------

    /** pod_id -> timestamp (seconds) of last diagnosis (dedup window) */
    public static final Map<String, Double> processedPods = new ConcurrentHashMap<>();

    /** pod_id -> timestamp (seconds) first observed in a failing state */
    public static final Map<String, Double> waitingPods = new ConcurrentHashMap<>();

    private static double nowSeconds() {
        return System.currentTimeMillis() / 1000.0;
    }

    // ----------------------------------------------------------------------
    // Safe extraction helpers
    // ----------------------------------------------------------------------

    /**
     * Extract a human-readable failure reason from all possible locations.
     *
     * <p>Checks, in order: container waiting-state reasons, container current terminated-state
     * reasons, container last-terminated reasons, pod-level condition reasons, and finally the
     * pod phase. Regular and init containers are both checked. Never throws - returns "Unknown"
     * on any missing/unexpected structure.
     */
    public static String getFailureReason(Pod pod) {
        try {
            var status = pod.getStatus();
            if (status == null) return "Unknown";

            List<ContainerStatus> containerStatuses = new ArrayList<>();
            if (status.getContainerStatuses() != null) containerStatuses.addAll(status.getContainerStatuses());
            if (status.getInitContainerStatuses() != null) containerStatuses.addAll(status.getInitContainerStatuses());

            // 1) Currently waiting (e.g. CrashLoopBackOff, ImagePullBackOff)
            for (ContainerStatus cs : containerStatuses) {
                ContainerState state = cs.getState();
                if (state != null && state.getWaiting() != null && state.getWaiting().getReason() != null) {
                    return state.getWaiting().getReason();
                }
            }

            // 2) Currently terminated (e.g. Error, OOMKilled - container is down now)
            for (ContainerStatus cs : containerStatuses) {
                ContainerState state = cs.getState();
                if (state != null && state.getTerminated() != null && state.getTerminated().getReason() != null) {
                    return state.getTerminated().getReason();
                }
            }

            // 3) Last terminated (previous run's reason, before the latest restart)
            for (ContainerStatus cs : containerStatuses) {
                ContainerState lastState = cs.getLastState();
                if (lastState != null && lastState.getTerminated() != null && lastState.getTerminated().getReason() != null) {
                    return lastState.getTerminated().getReason();
                }
            }

            // 4) Pod-level conditions (e.g. Evicted, Unschedulable)
            if (status.getConditions() != null) {
                for (PodCondition cond : status.getConditions()) {
                    if (cond.getReason() != null) return cond.getReason();
                }
            }

            // 5) Fallback to the pod phase
            if (status.getPhase() != null) return status.getPhase();
        } catch (Exception e) {
            // fall through to Unknown
        }
        return "Unknown";
    }

    /** Return the highest restart count across ALL containers (regular + init). Returns 0 if
     * containerStatuses is missing/empty (e.g. pod still Pending). */
    public static int getMaxRestartCount(Pod pod) {
        try {
            var status = pod.getStatus();
            if (status == null) return 0;

            List<ContainerStatus> statuses = new ArrayList<>();
            if (status.getContainerStatuses() != null) statuses.addAll(status.getContainerStatuses());
            if (status.getInitContainerStatuses() != null) statuses.addAll(status.getInitContainerStatuses());
            if (statuses.isEmpty()) return 0;

            int max = 0;
            for (ContainerStatus cs : statuses) {
                Integer rc = cs.getRestartCount();
                if (rc != null && rc > max) max = rc;
            }
            return max;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Classify how urgent a failure is for downstream triage/alerting.
     *
     * <p>{@code reason} (e.g. CrashLoopBackOff, ImagePullBackOff) drives most of the
     * classification since that's where container-level failure info actually lives; {@code
     * status} (pod phase) is only meaningful on its own for Pending.
     */
    public static String classifySeverity(String status, String reason, int restartCount) {
        if ("CrashLoopBackOff".equals(reason)) {
            return restartCount >= 5 ? "CRITICAL" : "HIGH";
        } else if (Set.of("ImagePullBackOff", "ImageInspectError", "CreateContainerConfigError", "ErrImagePull")
                .contains(reason)
            || "Failed".equals(status)) {
            return "CRITICAL";
        } else if ("Pending".equals(status)) {
            return "MEDIUM";
        }
        return "MEDIUM";
    }

    /** Return pod age in seconds based on creationTimestamp. 0 on failure. */
    public static double podAgeSeconds(Pod pod) {
        try {
            String created = pod.getMetadata().getCreationTimestamp();
            if (created == null) return 0;
            Instant createdInstant = Instant.parse(created);
            return (System.currentTimeMillis() - createdInstant.toEpochMilli()) / 1000.0;
        } catch (Exception e) {
            return 0;
        }
    }

    /** Safely pull out everything downstream diagnosis needs from a pod object. */
    public static PodInfo extractPodInfo(Pod pod) {
        String podName = "unknown";
        String namespace = "unknown";
        try {
            if (pod.getMetadata() != null) {
                if (pod.getMetadata().getName() != null) podName = pod.getMetadata().getName();
                if (pod.getMetadata().getNamespace() != null) namespace = pod.getMetadata().getNamespace();
            }
        } catch (Exception e) {
            // keep defaults
        }

        String podId = namespace + "/" + podName;

        String status = "Unknown";
        try {
            if (pod.getStatus() != null && pod.getStatus().getPhase() != null) {
                status = pod.getStatus().getPhase();
            }
        } catch (Exception e) {
            // keep default
        }

        String reason = getFailureReason(pod);
        int restartCount = getMaxRestartCount(pod);

        return new PodInfo(podId, podName, namespace, status, reason, restartCount,
            classifySeverity(status, reason, restartCount), pod);
    }

    // ----------------------------------------------------------------------
    // Decision logic (all loophole fixes live here)
    // ----------------------------------------------------------------------

    /**
     * Return true only if this pod should be diagnosed right now.
     *
     * <p>Encodes every loophole fix: namespace filtering, dedup window, eviction skip,
     * pod-recovery detection, new-pod age filtering, restart-count thresholds, and the
     * transient-failure wait (TRANSIENT_WAIT_SECONDS). Mutates the static {@code waitingPods} /
     * {@code processedPods} maps as a side effect since this is called once per relevant event
     * and needs to remember state across calls.
     */
    public static boolean shouldDiagnoseNow(String podId, String podName, String namespace, Pod pod) {
        double now = nowSeconds();

        // Loophole fix: namespace permissions - never touch system namespaces.
        if (EXCLUDED_NAMESPACES.contains(namespace)) {
            return false;
        }

        // Loophole fix: deduplication - skip if diagnosed within the last 5 min.
        Double lastDiagnosed = processedPods.get(podId);
        if (lastDiagnosed != null && (now - lastDiagnosed) < DEDUP_WINDOW_SECONDS) {
            return false;
        }

        PodInfo info = extractPodInfo(pod);
        String reason = info.reason();
        String status = info.status();
        int restartCount = info.restartCount();

        // Loophole fix: pod eviction - evicted pods aren't actionable failures.
        if ("Evicted".equals(reason)) {
            waitingPods.remove(podId);
            return false;
        }

        boolean isFailureState = FAILURE_REASONS.contains(reason) || FAILURE_PHASES.contains(status);

        // Loophole fix: pod recovery detection - if it's no longer failing, drop it from the
        // waiting set instead of diagnosing.
        if (!isFailureState) {
            waitingPods.remove(podId);
            return false;
        }

        // Loophole fix: new pod initialization - a brand-new Pending pod is probably still
        // being scheduled, not actually stuck.
        if ("Pending".equals(status) && podAgeSeconds(pod) < PENDING_MIN_AGE_SECONDS) {
            return false;
        }

        // Loophole fix: transient failure - require the pod to have been observed failing
        // continuously for TRANSIENT_WAIT_SECONDS before diagnosing. The clock starts the
        // moment the pod is first seen failing, independent of restart count. This naturally
        // filters out self-recovering pods, since a recovered pod hits the `!isFailureState`
        // branch above on its next event and gets dropped from waitingPods before the wait
        // elapses.
        Double firstSeen = waitingPods.get(podId);
        if (firstSeen == null) {
            waitingPods.put(podId, now);
            return false;
        }

        if ((now - firstSeen) < TRANSIENT_WAIT_SECONDS) {
            return false;
        }

        // Loophole fix: restart count patterns (checked once the wait has elapsed, so a
        // fast-accumulating CrashLoopBackOff doesn't need to wait the full
        // TRANSIENT_WAIT_SECONDS again after crossing the threshold).
        //   - CrashLoopBackOff: require >= MIN_RESTART_COUNT_CRASHLOOP restarts across ALL
        //     containers. If not met yet, keep waiting (don't reset the clock) until it is.
        //   - Failed (incl. restartPolicy=Never, which never accrues restarts): diagnose
        //     immediately, no restart requirement.
        if ("CrashLoopBackOff".equals(reason) && restartCount < MIN_RESTART_COUNT_CRASHLOOP) {
            return false;
        }

        // All checks passed - diagnose now.
        waitingPods.remove(podId);
        processedPods.put(podId, now);
        return true;
    }

    // ----------------------------------------------------------------------
    // Memory management
    // ----------------------------------------------------------------------

    /** Remove stale entries from processedPods and waitingPods. Prevents unbounded map growth
     * on long-running watchers with lots of pod churn. Called periodically from the main watch
     * loop. */
    public static void cleanupOldEntries() {
        double now = nowSeconds();

        int staleProcessed = 0;
        for (var entry : processedPods.entrySet()) {
            if ((now - entry.getValue()) > DEDUP_WINDOW_SECONDS) {
                processedPods.remove(entry.getKey());
                staleProcessed++;
            }
        }

        int staleWaiting = 0;
        for (var entry : waitingPods.entrySet()) {
            if ((now - entry.getValue()) > WAITING_STALE_TIMEOUT_SECONDS) {
                waitingPods.remove(entry.getKey());
                staleWaiting++;
            }
        }

        if (staleProcessed > 0 || staleWaiting > 0) {
            System.out.println("[CLEANUP] removed " + staleProcessed + " processedPods, "
                + staleWaiting + " waitingPods entries (processed=" + processedPods.size()
                + ", waiting=" + waitingPods.size() + ")");
        }
    }

    // ----------------------------------------------------------------------
    // Diagnosis handoff (Step 2)
    // ----------------------------------------------------------------------

    // Lazily-created DiagnosisModule instance used by triggerDiagnosis(). Left null until
    // either the first diagnosis is dispatched (defaults to mocks) or setDiagnosisModule()
    // injects a configured one (e.g. with real K8s + Claude clients on hackathon day).
    private static volatile DiagnosisModuleLike diagnosisModuleInstance = null;

    // Virtual threads (JDK 21+): cheap enough to spin up one per diagnosis without a pool,
    // and never block the watch loop's own thread.
    private static final ExecutorService DIAGNOSIS_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * Inject a configured DiagnosisModule for triggerDiagnosis() to use. Call this before
     * watchPodEvents() to swap in real clients:
     *
     * <pre>{@code
     * DetectionModule.setDiagnosisModule(new DiagnosisModule(
     *     new RealK8sClient(), new RealClaudeClient(apiKey)));
     * }</pre>
     */
    public static void setDiagnosisModule(DiagnosisModuleLike module) {
        diagnosisModuleInstance = module;
    }

    private static DiagnosisModuleLike getDiagnosisModule() {
        DiagnosisModuleLike instance = diagnosisModuleInstance;
        if (instance == null) {
            synchronized (DetectionModule.class) {
                if (diagnosisModuleInstance == null) {
                    diagnosisModuleInstance = new DiagnosisModule();
                }
                instance = diagnosisModuleInstance;
            }
        }
        return instance;
    }

    /**
     * Hand a failing pod off to Step 2 without blocking the watch loop.
     *
     * <p>Runs DiagnosisModule.diagnose() on a virtual thread - a real Claude call can take
     * several seconds, and shouldDiagnoseNow() already guarantees this fires at most once per
     * pod per dedup window, so a thread-per-diagnosis is enough at hackathon scale (a handful of
     * concurrent failures, not a flood).
     */
    public static void triggerDiagnosis(PodInfo podInfo) {
        DIAGNOSIS_EXECUTOR.submit(() -> {
            try {
                DiagnosisResult diagnosis = getDiagnosisModule().diagnose(podInfo);
                System.out.println("→ DIAGNOSIS: " + podInfo.podName()
                    + " root_cause=" + diagnosis.rootCause()
                    + " severity=" + diagnosis.severity()
                    + " confidence=" + diagnosis.confidence()
                    + " fix=" + diagnosis.recommendedFix());
            } catch (Exception e) {
                System.out.println("[ERROR] diagnosis failed for " + podInfo.podName() + ": " + e);
            }
        });
    }

    // ----------------------------------------------------------------------
    // Main event-driven watch loop
    // ----------------------------------------------------------------------

    private static void handlePodEvent(Pod pod) {
        String namespace = pod.getMetadata() != null ? pod.getMetadata().getNamespace() : null;
        String podName = pod.getMetadata() != null ? pod.getMetadata().getName() : null;
        if (namespace == null || podName == null) return;
        if (EXCLUDED_NAMESPACES.contains(namespace)) return;

        String podId = namespace + "/" + podName;

        if (shouldDiagnoseNow(podId, podName, namespace, pod)) {
            PodInfo info = extractPodInfo(pod);
            System.out.println("DETECTED: Pod failure - " + podName + " [" + namespace
                + "] reason=" + info.reason() + " restarts=" + info.restartCount()
                + " severity=" + info.severity());
            triggerDiagnosis(info);
        }
    }

    /**
     * Subscribe to pod events across all namespaces and detect failures.
     *
     * <p>Event-driven (uses the Kubernetes Watch API, not polling). Automatically reconnects on
     * disconnection after a 5s delay. Runs cleanupOldEntries() every CLEANUP_EVERY_N_EVENTS
     * events to bound memory growth.
     */
    public static void watchPodEvents() {
        KubernetesClient client = new KubernetesClientBuilder().build();
        AtomicInteger eventCount = new AtomicInteger(0);

        System.out.println("[INFO] Sentinel detection module starting - watching all namespaces "
            + "(excluding " + EXCLUDED_NAMESPACES + ")");

        startWatch(client, eventCount);

        // Keep the main thread alive - the watch itself runs on client-internal threads.
        Object lock = new Object();
        synchronized (lock) {
            try {
                lock.wait();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void startWatch(KubernetesClient client, AtomicInteger eventCount) {
        client.pods().inAnyNamespace().watch(new Watcher<Pod>() {
            @Override
            public void eventReceived(Action action, Pod pod) {
                eventCount.incrementAndGet();
                try {
                    handlePodEvent(pod);
                } catch (Exception e) {
                    System.out.println("[WARN] failed to process event: " + e);
                }
                if (eventCount.get() % CLEANUP_EVERY_N_EVENTS == 0) {
                    cleanupOldEntries();
                }
            }

            @Override
            public void onClose(WatcherException cause) {
                System.out.println("[ERROR] watch disconnected (" + cause
                    + "). Reconnecting in " + RECONNECT_DELAY_SECONDS + "s...");
                scheduleReconnect(client, eventCount);
            }

            @Override
            public void onClose() {
                System.out.println("[INFO] watch stream ended. Reconnecting in "
                    + RECONNECT_DELAY_SECONDS + "s...");
                scheduleReconnect(client, eventCount);
            }
        });
    }

    private static void scheduleReconnect(KubernetesClient client, AtomicInteger eventCount) {
        Thread reconnectThread = new Thread(() -> {
            try {
                Thread.sleep(RECONNECT_DELAY_SECONDS * 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            startWatch(client, eventCount);
        });
        reconnectThread.setDaemon(true);
        reconnectThread.start();
    }

    public static void main(String[] args) {
        watchPodEvents();
    }
}
