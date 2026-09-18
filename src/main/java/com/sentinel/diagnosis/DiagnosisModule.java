package com.sentinel.diagnosis;

import com.sentinel.detection.PodInfo;
import io.fabric8.kubernetes.api.model.ContainerState;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.Pod;
import java.util.List;

/**
 * Orchestrates fetching K8s context and getting a Claude diagnosis.
 *
 * <p>NOW (testing): {@code new DiagnosisModule()} // MockK8sClient + MockClaudeClient
 * <br>HACKATHON DAY (real): {@code new DiagnosisModule(new RealK8sClient(), new
 * RealClaudeClient(apiKey))}
 * <br>{@code diagnose()} is identical either way.
 */
public class DiagnosisModule implements DiagnosisModuleLike {

    private static final int MAX_EVENTS = 10;

    private final K8sDataFetcher k8sClient;
    private final ClaudeAnalyzer claudeClient;

    public DiagnosisModule() {
        this(new MockK8sClient(), new MockClaudeClient());
    }

    public DiagnosisModule(K8sDataFetcher k8sClient, ClaudeAnalyzer claudeClient) {
        this.k8sClient = k8sClient;
        this.claudeClient = claudeClient;
    }

    @Override
    public DiagnosisResult diagnose(PodInfo podInfo) {
        String podName = podInfo.podName() != null ? podInfo.podName() : "unknown";
        String namespace = podInfo.namespace() != null ? podInfo.namespace() : "unknown";
        String podId = podInfo.podId() != null && !podInfo.podId().isEmpty()
            ? podInfo.podId() : namespace + "/" + podName;

        // Loophole 1: pod deleted before diagnosis even starts.
        String currentStatus;
        try {
            currentStatus = k8sClient.getPodStatus(namespace, podName);
        } catch (Exception e) {
            return build(podId, podName, namespace, new ClaudeAnalysis(
                "Pod deleted", "LOW", "No action needed - pod no longer exists", 0.0,
                List.of("Pod lookup failed: " + e.getMessage())), "", List.of());
        }

        // Loophole 5: pod recovered on its own - skip the Claude call to save API cost/latency.
        if ("Running".equals(currentStatus)) {
            return build(podId, podName, namespace, new ClaudeAnalysis(
                "Pod recovered", "LOW", "No action needed - pod is now Running", 1.0,
                List.of("Pod status re-checked as Running at diagnosis time")), "", List.of());
        }

        Pod podObject = podInfo.podObject();

        // Loophole 4: multiple containers - fetch the primary container only.
        String primaryContainer = getPrimaryContainerName(podObject);
        String logs;
        try {
            String fetched = k8sClient.fetchPodLogs(namespace, podName, primaryContainer, false);
            logs = fetched != null ? fetched : "";
        } catch (Exception e) {
            logs = "";
        }

        // Loophole 6: init container failures - prepend init logs if one isn't Running/Completed
        // cleanly.
        String failedInit = getFailedInitContainer(podObject);
        if (failedInit != null) {
            String initLogs = null;
            try {
                initLogs = k8sClient.fetchInitContainerLogs(namespace, podName, failedInit);
            } catch (Exception ignored) {
                // leave initLogs null
            }
            if (initLogs != null && !initLogs.isEmpty()) {
                logs = "[Init container '" + failedInit + "' logs]\n" + initLogs
                    + "\n\n[Main container logs]\n" + logs;
            }
        }

        // Loophole 2: no logs available - tell Claude explicitly, don't fail.
        if (logs.trim().isEmpty()) {
            logs = "[No logs - check events]";
        }

        // Loophole 3: huge logs - truncate but keep error lines.
        logs = LogUtils.truncate(logs);

        List<EventInfo> events;
        try {
            List<EventInfo> fetched = k8sClient.fetchPodEvents(namespace, podName);
            events = fetched != null ? fetched : List.of();
        } catch (Exception e) {
            events = List.of();
        }
        if (events.size() > MAX_EVENTS) {
            events = events.subList(events.size() - MAX_EVENTS, events.size());
        }

        PodSpecInfo spec;
        try {
            spec = k8sClient.fetchPodSpec(podObject);
        } catch (Exception e) {
            spec = PodSpecInfo.unknown();
        }

        // Loophole 7: Claude API failure - degrade gracefully.
        ClaudeAnalysis result;
        try {
            result = claudeClient.analyze(podInfo, logs, events, spec);
        } catch (Exception e) {
            result = new ClaudeAnalysis(
                "Diagnosis pending",
                podInfo.severity() != null ? podInfo.severity() : "MEDIUM",
                "Manual review required - diagnosis engine unavailable",
                0.0,
                List.of("Claude analysis failed: " + e.getMessage()));
        }

        return build(podId, podName, namespace, result, logs, events);
    }

    private static DiagnosisResult build(
        String podId, String podName, String namespace, ClaudeAnalysis result,
        String rawLogs, List<EventInfo> rawEvents) {
        return new DiagnosisResult(
            podId, podName, namespace,
            result.rootCause(), result.severity(), result.recommendedFix(),
            result.confidence(), result.evidence(),
            System.currentTimeMillis() / 1000,
            rawLogs, rawEvents);
    }

    /** Loophole fix: multiple containers - only ever fetch the first (primary) container's
     * logs, never all of them. */
    private static String getPrimaryContainerName(Pod podObject) {
        try {
            return podObject.getSpec().getContainers().get(0).getName();
        } catch (Exception e) {
            return null;
        }
    }

    /** Loophole fix: init container failures. Returns the name of an init container that's
     * stuck waiting or terminated abnormally, or null if all init containers are
     * missing/running/completed cleanly. */
    private static String getFailedInitContainer(Pod podObject) {
        List<ContainerStatus> statuses;
        try {
            statuses = podObject.getStatus().getInitContainerStatuses();
            if (statuses == null) return null;
        } catch (Exception e) {
            return null;
        }

        for (ContainerStatus cs : statuses) {
            ContainerState state = cs.getState();
            if (state == null) continue;
            if (state.getWaiting() != null) return cs.getName();
            if (state.getTerminated() != null
                && state.getTerminated().getReason() != null
                && !"Completed".equals(state.getTerminated().getReason())) {
                return cs.getName();
            }
        }
        return null;
    }

    public static void main(String[] args) {
        PodInfo demoPodInfo = new PodInfo(
            "default/demo-pod", "demo-pod", "default", "Failed", "OOMKilled", 3, "HIGH", null);

        // Explicit podStatus="Failed" so this demo shows the full pipeline (logs/events/spec ->
        // Claude) rather than the default mock's podStatus="Unknown".
        DiagnosisModule demoModule = new DiagnosisModule(
            MockK8sClient.builder().podStatus("Failed").build(), new MockClaudeClient());
        DiagnosisResult diagnosis = demoModule.diagnose(demoPodInfo);
        System.out.println(diagnosis);
    }
}
