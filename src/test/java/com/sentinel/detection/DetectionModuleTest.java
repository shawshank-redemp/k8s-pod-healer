package com.sentinel.detection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sentinel.diagnosis.DiagnosisModuleLike;
import com.sentinel.diagnosis.DiagnosisResult;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerState;
import io.fabric8.kubernetes.api.model.ContainerStateBuilder;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.ContainerStatusBuilder;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodCondition;
import io.fabric8.kubernetes.api.model.PodConditionBuilder;
import io.fabric8.kubernetes.api.model.PodStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Covers the 6 original scenarios plus 4 additional ones (ImagePullBackOff, 1-restart
 * CrashLoopBackOff, long-Pending, non-blocking dispatch) against fabricated pod objects - no
 * real cluster required. Timing constants are shrunk in @BeforeEach and restored in @AfterEach
 * so the suite runs in seconds instead of minutes.
 */
class DetectionModuleTest {

    private double originalTransientWait;
    private double originalDedupWindow;
    private double originalWaitingStale;
    private double originalPendingMinAge;

    @BeforeEach
    void shrinkTimings() {
        originalTransientWait = DetectionModule.TRANSIENT_WAIT_SECONDS;
        originalDedupWindow = DetectionModule.DEDUP_WINDOW_SECONDS;
        originalWaitingStale = DetectionModule.WAITING_STALE_TIMEOUT_SECONDS;
        originalPendingMinAge = DetectionModule.PENDING_MIN_AGE_SECONDS;

        DetectionModule.TRANSIENT_WAIT_SECONDS = 0.5;
        DetectionModule.DEDUP_WINDOW_SECONDS = 2;
        DetectionModule.WAITING_STALE_TIMEOUT_SECONDS = 1;
        DetectionModule.PENDING_MIN_AGE_SECONDS = 1;

        DetectionModule.processedPods.clear();
        DetectionModule.waitingPods.clear();
    }

    @AfterEach
    void restoreTimings() {
        DetectionModule.TRANSIENT_WAIT_SECONDS = originalTransientWait;
        DetectionModule.DEDUP_WINDOW_SECONDS = originalDedupWindow;
        DetectionModule.WAITING_STALE_TIMEOUT_SECONDS = originalWaitingStale;
        DetectionModule.PENDING_MIN_AGE_SECONDS = originalPendingMinAge;
    }

    private static void sleep(double seconds) {
        try {
            Thread.sleep((long) (seconds * 1000));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private static Pod makePod(String name, String namespace, String phase, String reason,
                                int restartCount, double ageSeconds, boolean terminatedState,
                                List<Integer> extraContainerRestartCounts) {
        Instant created = Instant.now().minusMillis((long) (ageSeconds * 1000));

        ContainerState state;
        if (reason != null) {
            state = terminatedState
                ? new ContainerStateBuilder().withNewTerminated().withReason(reason).withExitCode(1).endTerminated().build()
                : new ContainerStateBuilder().withNewWaiting().withReason(reason).endWaiting().build();
        } else {
            state = new ContainerStateBuilder().build();
        }

        List<ContainerStatus> statuses = new ArrayList<>();
        statuses.add(new ContainerStatusBuilder()
            .withName("main").withImage("busybox").withImageID("").withReady(false)
            .withRestartCount(restartCount).withState(state).build());

        for (int i = 0; i < extraContainerRestartCounts.size(); i++) {
            statuses.add(new ContainerStatusBuilder()
                .withName("sidecar-" + i).withImage("busybox").withImageID("").withReady(false)
                .withRestartCount(extraContainerRestartCounts.get(i))
                .withState(new ContainerStateBuilder().build())
                .build());
        }

        return new Pod().toBuilder()
            .withMetadata(new ObjectMeta().toBuilder()
                .withName(name).withNamespace(namespace).withCreationTimestamp(created.toString()).build())
            .withStatus(new PodStatus().toBuilder().withPhase(phase).withContainerStatuses(statuses).build())
            .build();
    }

    private static Pod makePod(String name, String reason, int restartCount) {
        return makePod(name, "default", "Running", reason, restartCount, 120, false, List.of());
    }

    // ------------------------------------------------------------------
    // Test 1: Basic detection (CrashLoopBackOff, enough restarts, past the wait)
    // ------------------------------------------------------------------

    @Test
    void test1BasicDetection() {
        Pod pod = makePod("crash-test", "CrashLoopBackOff", 3);
        String podId = "default/crash-test";

        boolean first = DetectionModule.shouldDiagnoseNow(podId, "crash-test", "default", pod);
        assertFalse(first, "first sighting starts the wait, does not diagnose yet");

        sleep(DetectionModule.TRANSIENT_WAIT_SECONDS + 0.2);
        boolean second = DetectionModule.shouldDiagnoseNow(podId, "crash-test", "default", pod);
        assertTrue(second, "diagnoses after wait elapses");
    }

    // ------------------------------------------------------------------
    // Test 2: Deduplication
    // ------------------------------------------------------------------

    @Test
    void test2Deduplication() {
        Pod pod = makePod("dedup-test", "CrashLoopBackOff", 5);
        String podId = "default/dedup-test";

        DetectionModule.shouldDiagnoseNow(podId, "dedup-test", "default", pod);
        sleep(DetectionModule.TRANSIENT_WAIT_SECONDS + 0.2);
        assertTrue(DetectionModule.shouldDiagnoseNow(podId, "dedup-test", "default", pod), "diagnosed the first time");

        assertFalse(DetectionModule.shouldDiagnoseNow(podId, "dedup-test", "default", pod),
            "not re-diagnosed within dedup window");
    }

    // ------------------------------------------------------------------
    // Test 3: Transient failure (recovers before the wait elapses)
    // ------------------------------------------------------------------

    @Test
    void test3TransientFailure() {
        String podId = "default/flaky-test";

        Pod failingPod = makePod("flaky-test", "default", "Running", "Error", 0, 120, true, List.of());
        DetectionModule.shouldDiagnoseNow(podId, "flaky-test", "default", failingPod);
        assertTrue(DetectionModule.waitingPods.containsKey(podId), "pod added to waiting set");

        Pod healthyPod = makePod("flaky-test", "default", "Running", null, 0, 120, false, List.of());
        boolean recovered = DetectionModule.shouldDiagnoseNow(podId, "flaky-test", "default", healthyPod);
        assertFalse(recovered, "recovered pod is not diagnosed");
        assertFalse(DetectionModule.waitingPods.containsKey(podId), "recovered pod removed from waiting set");
    }

    // ------------------------------------------------------------------
    // Test 4: Restart count threshold for CrashLoopBackOff (MIN_RESTART_COUNT_CRASHLOOP=1)
    // ------------------------------------------------------------------

    @Test
    void test4RestartCount() {
        String podId = "default/restart-test";

        Pod zeroRestarts = makePod("restart-test", "CrashLoopBackOff", 0);
        DetectionModule.shouldDiagnoseNow(podId, "restart-test", "default", zeroRestarts);
        sleep(DetectionModule.TRANSIENT_WAIT_SECONDS + 0.2);
        assertFalse(DetectionModule.shouldDiagnoseNow(podId, "restart-test", "default", zeroRestarts),
            "blocked below restart threshold (restart_count=0)");

        // Restart count reaches 1 on a later event - only the sidecar restarted, confirming ALL
        // containers are checked, not just the first.
        Pod oneRestart = makePod("restart-test", "default", "Running", "CrashLoopBackOff", 0, 120, false, List.of(1));
        assertEquals(1, DetectionModule.getMaxRestartCount(oneRestart), "getMaxRestartCount picks the max across containers");
        assertTrue(DetectionModule.shouldDiagnoseNow(podId, "restart-test", "default", oneRestart),
            "diagnosed once restart_count >= 1");
    }

    // ------------------------------------------------------------------
    // Test 5: Namespace filtering
    // ------------------------------------------------------------------

    @Test
    void test5NamespaceFiltering() {
        Pod pod = makePod("system-pod", "kube-system", "Running", "CrashLoopBackOff", 10, 120, false, List.of());
        String podId = "kube-system/system-pod";

        assertFalse(DetectionModule.shouldDiagnoseNow(podId, "system-pod", "kube-system", pod),
            "kube-system pods are always skipped");
        assertFalse(DetectionModule.waitingPods.containsKey(podId), "kube-system pods never enter the waiting set");
    }

    // ------------------------------------------------------------------
    // Test 6: Pod recovery after starting the wait, cleaned from waitingPods
    // ------------------------------------------------------------------

    @Test
    void test6RecoveryAfterWaitStarted() {
        String podId = "default/recover-test";

        Pod failingPod = makePod("recover-test", "CrashLoopBackOff", 5);
        DetectionModule.shouldDiagnoseNow(podId, "recover-test", "default", failingPod);
        assertTrue(DetectionModule.waitingPods.containsKey(podId), "entered waitingPods on first failure sighting");

        sleep(DetectionModule.TRANSIENT_WAIT_SECONDS * 0.4); // well before the wait elapses

        Pod recoveredPod = makePod("recover-test", "default", "Running", null, 5, 120, false, List.of());
        assertFalse(DetectionModule.shouldDiagnoseNow(podId, "recover-test", "default", recoveredPod),
            "not diagnosed - recovered before wait elapsed");
        assertFalse(DetectionModule.waitingPods.containsKey(podId), "removed from waitingPods on recovery");
    }

    // ------------------------------------------------------------------
    // Extra: safe extraction on missing/edge-case data
    // ------------------------------------------------------------------

    @Test
    void testSafeExtractionEdgeCases() {
        Pod emptyPod = new Pod().toBuilder()
            .withMetadata(new ObjectMeta().toBuilder().withName("empty").withNamespace("default").build())
            .withStatus(new PodStatus().toBuilder().withPhase("Pending").build())
            .build();

        assertEquals("Pending", DetectionModule.getFailureReason(emptyPod),
            "getFailureReason falls back to phase when no container info");
        assertEquals(0, DetectionModule.getMaxRestartCount(emptyPod), "getMaxRestartCount is 0 with no containerStatuses");

        PodInfo info = DetectionModule.extractPodInfo(emptyPod);
        assertEquals("default/empty", info.podId());
        assertEquals("Pending", info.status());

        Pod evictedPod = makePod("evicted-test", "default", "Failed", null, 0, 120, false, List.of());
        PodCondition evictedCondition = new PodConditionBuilder()
            .withType("Ready").withStatus("False").withReason("Evicted").build();
        evictedPod.getStatus().setConditions(List.of(evictedCondition));

        assertFalse(DetectionModule.shouldDiagnoseNow("default/evicted-test", "evicted-test", "default", evictedPod),
            "evicted pods are skipped");
    }

    // ------------------------------------------------------------------
    // Test 7: ImagePullBackOff detection, classified CRITICAL
    // ------------------------------------------------------------------

    @Test
    void test7ImagePullBackoff() {
        String podId = "default/badimage-test";
        Pod pod = makePod("badimage-test", "ImagePullBackOff", 0);

        assertFalse(DetectionModule.shouldDiagnoseNow(podId, "badimage-test", "default", pod),
            "first sighting starts the wait, does not diagnose yet");

        sleep(DetectionModule.TRANSIENT_WAIT_SECONDS + 0.2);
        assertTrue(DetectionModule.shouldDiagnoseNow(podId, "badimage-test", "default", pod),
            "ImagePullBackOff diagnosed after wait (no restart requirement)");

        assertEquals("CRITICAL", DetectionModule.extractPodInfo(pod).severity(), "ImagePullBackOff classified as CRITICAL");
    }

    // ------------------------------------------------------------------
    // Test 8: CrashLoopBackOff with exactly 1 restart diagnoses without waiting for the old
    // 3-restart threshold
    // ------------------------------------------------------------------

    @Test
    void test8CrashloopOneRestart() {
        String podId = "default/onerestart-test";
        Pod pod = makePod("onerestart-test", "CrashLoopBackOff", 1);

        assertFalse(DetectionModule.shouldDiagnoseNow(podId, "onerestart-test", "default", pod),
            "first sighting starts the wait, does not diagnose yet");

        sleep(DetectionModule.TRANSIENT_WAIT_SECONDS + 0.2);
        assertTrue(DetectionModule.shouldDiagnoseNow(podId, "onerestart-test", "default", pod),
            "diagnosed with only 1 restart (threshold lowered to 1)");

        assertEquals("HIGH", DetectionModule.extractPodInfo(pod).severity(),
            "1 restart classified as HIGH (not yet CRITICAL, needs >=5)");
    }

    // ------------------------------------------------------------------
    // Test 9: Pod Pending for 40s is diagnosed with MEDIUM severity
    // ------------------------------------------------------------------

    @Test
    void test9Pending40Seconds() {
        String podId = "default/stuckpending-test";
        Pod pod = makePod("stuckpending-test", "default", "Pending", null, 0, 40, false, List.of());

        assertFalse(DetectionModule.shouldDiagnoseNow(podId, "stuckpending-test", "default", pod),
            "first sighting starts the wait, does not diagnose yet");

        sleep(DetectionModule.TRANSIENT_WAIT_SECONDS + 0.2);
        assertTrue(DetectionModule.shouldDiagnoseNow(podId, "stuckpending-test", "default", pod),
            "40s-old Pending pod diagnosed (past the age filter)");

        assertEquals("MEDIUM", DetectionModule.extractPodInfo(pod).severity(), "long-Pending pod classified as MEDIUM");
    }

    // ------------------------------------------------------------------
    // Test 10: triggerDiagnosis dispatches to Step 2 without blocking the caller
    // ------------------------------------------------------------------

    @Test
    void test10TriggerDiagnosisNonBlocking() throws InterruptedException {
        AtomicReference<String> calledWith = new AtomicReference<>();
        java.util.concurrent.CountDownLatch ran = new java.util.concurrent.CountDownLatch(1);

        DiagnosisModuleLike slowSpy = podInfo -> {
            sleep(0.3);
            calledWith.set(podInfo.podName());
            ran.countDown();
            return new DiagnosisResult(podInfo.podId(), podInfo.podName(), podInfo.namespace(),
                "test", "LOW", "n/a", 1.0, List.of(), System.currentTimeMillis() / 1000, "", List.of());
        };

        DetectionModule.setDiagnosisModule(slowSpy);
        try {
            long start = System.currentTimeMillis();
            DetectionModule.triggerDiagnosis(new PodInfo(
                "default/spy-test", "spy-test", "default", "Failed", "Error", 0, "LOW", null));
            long elapsedMs = System.currentTimeMillis() - start;
            assertTrue(elapsedMs < 100, "triggerDiagnosis returns immediately, doesn't wait for diagnose()");

            assertTrue(ran.await(2, java.util.concurrent.TimeUnit.SECONDS), "diagnosis eventually runs in the background");
            assertEquals("spy-test", calledWith.get());
        } finally {
            DetectionModule.setDiagnosisModule(null);
        }
    }
}
