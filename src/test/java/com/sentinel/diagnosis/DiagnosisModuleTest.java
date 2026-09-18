package com.sentinel.diagnosis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sentinel.detection.PodInfo;
import io.fabric8.kubernetes.api.model.ContainerStateBuilder;
import io.fabric8.kubernetes.api.model.ContainerStatusBuilder;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodSpec;
import io.fabric8.kubernetes.api.model.PodStatus;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Covers the 5 core scenarios from the spec, plus one extra test per remaining loophole (deleted
 * pod, log truncation, primary-container-only fetch, init container logs, Claude API failure)
 * that the 5 core scenarios don't already exercise. Everything runs against
 * MockK8sClient/MockClaudeClient - no real cluster or Claude API key needed.
 */
class DiagnosisModuleTest {

    private static PodInfo podInfo(String status, String reason, int restartCount, String severity, Pod podObject) {
        return new PodInfo("default/test-pod", "test-pod", "default", status, reason, restartCount, severity, podObject);
    }

    private static Pod podObject(List<String> containerNames, String initContainerReason) {
        List<io.fabric8.kubernetes.api.model.Container> containers = containerNames.stream()
            .map(n -> new ContainerBuilder().withName(n).withImage("busybox").build())
            .toList();

        List<io.fabric8.kubernetes.api.model.ContainerStatus> initStatuses = initContainerReason == null
            ? List.of()
            : List.of(new ContainerStatusBuilder()
                .withName("init-setup").withImage("busybox").withImageID("").withReady(false)
                .withRestartCount(0)
                .withState(new ContainerStateBuilder().withNewTerminated()
                    .withReason(initContainerReason).withExitCode(1).endTerminated().build())
                .build());

        PodStatus status = new PodStatus().toBuilder()
            .withPhase("Running")
            .withInitContainerStatuses(initStatuses)
            .build();

        return new Pod().toBuilder()
            .withMetadata(new ObjectMeta().toBuilder().withName("test-pod").withNamespace("default").build())
            .withSpec(new PodSpec().toBuilder().withContainers(containers).build())
            .withStatus(status)
            .build();
    }

    // ------------------------------------------------------------------
    // TEST 1: OOMKilled pod
    // ------------------------------------------------------------------

    @Test
    void test1OomKilled() {
        PodInfo info = podInfo("Failed", "OOMKilled", 3, "HIGH", null);

        MockK8sClient mockK8s = MockK8sClient.builder()
            .podStatus("Failed")
            .logs("Starting app...\nmemory exceeded\nContainer killed with exit code 137")
            .events(List.of(new EventInfo("OOMKilling", "Memory cgroup out of memory", "123")))
            .spec(new PodSpecInfo("myapp:latest", "512Mi", "250m", "256Mi", "100m", Map.of(), null, null))
            .build();
        MockClaudeClient mockClaude = new MockClaudeClient(new ClaudeAnalysis(
            "Out of memory", "CRITICAL", "Increase memory limit above 512Mi", 0.95,
            List.of("exit code 137", "memory exceeded in logs")));

        DiagnosisModule module = new DiagnosisModule(mockK8s, mockClaude);
        DiagnosisResult diagnosis = module.diagnose(info);

        assertEquals("Out of memory", diagnosis.rootCause());
        assertEquals("CRITICAL", diagnosis.severity());
        assertEquals(0.95, diagnosis.confidence());
        assertEquals("default/test-pod", diagnosis.podId());
        assertTrue(diagnosis.rawLogs().contains("137"), "raw_logs contains exit code evidence");
    }

    // ------------------------------------------------------------------
    // TEST 2: ImagePullBackOff
    // ------------------------------------------------------------------

    @Test
    void test2ImagePullBackoff() {
        PodInfo info = podInfo("Pending", "ImagePullBackOff", 0, "CRITICAL", null);

        MockK8sClient mockK8s = MockK8sClient.builder()
            .podStatus("Pending")
            .logs("")
            .events(List.of(new EventInfo("FailedPulling", "rpc error: image myapp:typo123 not found", "123")))
            .spec(new PodSpecInfo("myapp:typo123", "256Mi", "200m", "128Mi", "100m", Map.of(), null, null))
            .build();
        MockClaudeClient mockClaude = new MockClaudeClient(new ClaudeAnalysis(
            "Image not found", "CRITICAL", "Fix the image tag typo123 to a valid tag", 0.95,
            List.of("FailedPulling event", "image tag typo123")));

        DiagnosisResult diagnosis = new DiagnosisModule(mockK8s, mockClaude).diagnose(info);

        assertEquals("Image not found", diagnosis.rootCause());
        assertEquals("CRITICAL", diagnosis.severity());
        assertEquals(0.95, diagnosis.confidence());
        assertEquals("[No logs - check events]", diagnosis.rawLogs());
    }

    // ------------------------------------------------------------------
    // TEST 3: No logs available
    // ------------------------------------------------------------------

    @Test
    void test3NoLogsAvailable() {
        PodInfo info = podInfo("CrashLoopBackOff", "CrashLoopBackOff", 0, "HIGH", null);

        MockK8sClient mockK8s = MockK8sClient.builder()
            .podStatus("CrashLoopBackOff")
            .logs("")
            .events(List.of(new EventInfo("BackOff", "Back-off restarting failed container", "123")))
            .build();
        MockClaudeClient mockClaude = new MockClaudeClient(new ClaudeAnalysis(
            "Unable to diagnose without logs", "LOW", "Check events and add application logging", 0.3,
            List.of("No logs available", "BackOff event present")));

        DiagnosisResult diagnosis = new DiagnosisModule(mockK8s, mockClaude).diagnose(info);

        assertEquals("Unable to diagnose without logs", diagnosis.rootCause());
        assertEquals(0.3, diagnosis.confidence());
        assertEquals("[No logs - check events]", diagnosis.rawLogs());
    }

    // ------------------------------------------------------------------
    // TEST 4: Pod recovered (skip diagnosis, save the Claude call)
    // ------------------------------------------------------------------

    @Test
    void test4PodRecovered() {
        PodInfo info = podInfo("CrashLoopBackOff", "CrashLoopBackOff", 5, "HIGH", null);

        MockK8sClient mockK8s = MockK8sClient.builder().podStatus("Running").build();
        MockClaudeClient mockClaude = new MockClaudeClient(); // should never be called

        DiagnosisResult diagnosis = new DiagnosisModule(mockK8s, mockClaude).diagnose(info);

        assertEquals("Pod recovered", diagnosis.rootCause());
        assertEquals(0, mockClaude.callCount.get(), "Claude was never called (saved the API call)");
    }

    // ------------------------------------------------------------------
    // TEST 5: Liveness probe killing pod
    // ------------------------------------------------------------------

    @Test
    void test5LivenessProbeTooAggressive() {
        PodInfo info = podInfo("CrashLoopBackOff", "CrashLoopBackOff", 6, "HIGH", null);

        MockK8sClient mockK8s = MockK8sClient.builder()
            .podStatus("CrashLoopBackOff")
            .logs("App started\nHealthy\nHealthy\nContainer killed")
            .events(List.of(new EventInfo("Unhealthy", "Liveness probe failed: HTTP probe failed with statuscode: 500", "123")))
            .spec(new PodSpecInfo("myapp:latest", "256Mi", "200m", "128Mi", "100m", Map.of(),
                new ProbeInfo(null, 5, 5, 1), null))
            .build();
        MockClaudeClient mockClaude = new MockClaudeClient(new ClaudeAnalysis(
            "Liveness probe too aggressive", "HIGH", "Increase liveness probe timeoutSeconds and failureThreshold", 0.80,
            List.of("Liveness probe failed events", "logs show Healthy right before kill")));

        DiagnosisResult diagnosis = new DiagnosisModule(mockK8s, mockClaude).diagnose(info);

        assertTrue(diagnosis.rootCause().toLowerCase().contains("probe"), "root_cause mentions the probe");
        assertTrue(diagnosis.recommendedFix().toLowerCase().contains("probe"), "recommended_fix suggests a probe adjustment");
        assertEquals(0.80, diagnosis.confidence());
    }

    // ------------------------------------------------------------------
    // EXTRA: Loophole 1 - pod deleted before diagnosis
    // ------------------------------------------------------------------

    @Test
    void test6PodDeleted() {
        PodInfo info = podInfo("Running", "Unknown", 0, "MEDIUM", null);

        MockK8sClient mockK8s = MockK8sClient.builder().raiseNotFound(true).build();
        MockClaudeClient mockClaude = new MockClaudeClient();

        DiagnosisResult diagnosis = new DiagnosisModule(mockK8s, mockClaude).diagnose(info);

        assertEquals("Pod deleted", diagnosis.rootCause());
        assertEquals(0.0, diagnosis.confidence());
        assertEquals(0, mockClaude.callCount.get());
    }

    // ------------------------------------------------------------------
    // EXTRA: Loophole 3 - log truncation keeps error lines out of the tail window
    // ------------------------------------------------------------------

    @Test
    void test7LogTruncationKeepsErrorLines() {
        String filler = "normal log line, nothing interesting here\n".repeat(400);
        String hugeLog = "FATAL: disk full during startup\n" + filler;
        assertTrue(hugeLog.length() > LogUtils.LOG_SIZE_THRESHOLD_CHARS, "fixture is actually over the threshold");

        String truncated = LogUtils.truncate(hugeLog);
        assertTrue(truncated.length() < hugeLog.length(), "truncated output is shorter than the original");
        assertTrue(truncated.contains("FATAL: disk full during startup"),
            "early FATAL line survives truncation despite being outside the tail");

        String shortLog = "just a short log, no truncation needed";
        assertEquals(shortLog, LogUtils.truncate(shortLog), "short logs pass through unchanged");
        assertEquals("[No logs available]", LogUtils.truncate(""), "empty logs get the placeholder");
    }

    // ------------------------------------------------------------------
    // EXTRA: Loophole 4 - multiple containers, primary only
    // ------------------------------------------------------------------

    @Test
    void test8PrimaryContainerOnly() {
        Pod pod = podObject(List.of("main-app", "sidecar-proxy", "sidecar-logger"), null);
        PodInfo info = podInfo("Running", "Unknown", 0, "MEDIUM", pod);

        MockK8sClient mockK8s = MockK8sClient.builder().podStatus("Failed").build();

        new DiagnosisModule(mockK8s, new MockClaudeClient()).diagnose(info);

        assertEquals("main-app", mockK8s.lastLogsContainer, "logs fetched for the first container only");
    }

    // ------------------------------------------------------------------
    // EXTRA: Loophole 6 - init container failure logs get included
    // ------------------------------------------------------------------

    @Test
    void test9InitContainerFailure() {
        Pod pod = podObject(List.of("main"), "Error");
        PodInfo info = podInfo("Running", "Unknown", 0, "MEDIUM", pod);

        MockK8sClient mockK8s = MockK8sClient.builder()
            .podStatus("Failed")
            .logs("main container starting normally")
            .initContainerLogs("init-setup: failed to mount config volume")
            .build();

        DiagnosisResult diagnosis = new DiagnosisModule(mockK8s, new MockClaudeClient()).diagnose(info);

        assertTrue(diagnosis.rawLogs().contains("failed to mount config volume"), "init container logs included in raw_logs");
        assertTrue(diagnosis.rawLogs().contains("main container starting normally"), "main container logs still present too");
    }

    // ------------------------------------------------------------------
    // EXTRA: Loophole 7 - Claude API failure degrades gracefully
    // ------------------------------------------------------------------

    @Test
    void test10ClaudeApiFailure() {
        PodInfo info = podInfo("Failed", "Unknown", 0, "HIGH", null);

        MockK8sClient mockK8s = MockK8sClient.builder().podStatus("Failed").build();
        MockClaudeClient mockClaude = MockClaudeClient.thatFails();

        DiagnosisResult diagnosis = new DiagnosisModule(mockK8s, mockClaude).diagnose(info);

        assertEquals("Diagnosis pending", diagnosis.rootCause());
        assertEquals(0.0, diagnosis.confidence());
        assertEquals("HIGH", diagnosis.severity(), "severity falls back to pod_info severity");
    }
}
