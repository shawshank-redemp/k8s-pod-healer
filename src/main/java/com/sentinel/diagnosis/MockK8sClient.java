package com.sentinel.diagnosis;

import io.fabric8.kubernetes.api.model.Pod;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Canned Kubernetes responses, configurable per test scenario.
 *
 * <p>podStatus defaults to "Unknown" rather than "Running" on purpose: this class is also what an
 * unconfigured DiagnosisModule falls back to, and DiagnosisModule.diagnose() treats a "Running"
 * status as "pod recovered, skip diagnosis". Defaulting to "Running" would make every diagnosis
 * dispatched through the default, unconfigured pipeline silently report "Pod recovered"
 * regardless of the pod's actual state - exactly the bug caught testing the port of this same
 * logic against a live Kind cluster.
 */
public class MockK8sClient implements K8sDataFetcher {

    private final String logs;
    private final List<EventInfo> events;
    private final PodSpecInfo spec;
    private final String podStatus;
    private final String initContainerLogs;
    private final boolean raiseNotFound;

    // Spy state so tests can assert on how the client was called.
    public volatile String lastLogsContainer;
    public final AtomicInteger callCount = new AtomicInteger(0);

    public static final class Builder {
        private String logs = "[MockLog] Container crashed with exit code 137";
        private List<EventInfo> events =
            List.of(new EventInfo("BackOff", "Pod crashing", String.valueOf(System.currentTimeMillis() / 1000)));
        private PodSpecInfo spec = new PodSpecInfo(
            "mock-image:latest", "512Mi", "100m", "256Mi", "50m", java.util.Map.of(), null, null);
        private String podStatus = "Unknown";
        private String initContainerLogs = null;
        private boolean raiseNotFound = false;

        public Builder logs(String v) { this.logs = v; return this; }
        public Builder events(List<EventInfo> v) { this.events = v; return this; }
        public Builder spec(PodSpecInfo v) { this.spec = v; return this; }
        public Builder podStatus(String v) { this.podStatus = v; return this; }
        public Builder initContainerLogs(String v) { this.initContainerLogs = v; return this; }
        public Builder raiseNotFound(boolean v) { this.raiseNotFound = v; return this; }
        public MockK8sClient build() { return new MockK8sClient(this); }
    }

    public static Builder builder() {
        return new Builder();
    }

    private MockK8sClient(Builder b) {
        this.logs = b.logs;
        this.events = b.events;
        this.spec = b.spec;
        this.podStatus = b.podStatus;
        this.initContainerLogs = b.initContainerLogs;
        this.raiseNotFound = b.raiseNotFound;
    }

    public MockK8sClient() {
        this(new Builder());
    }

    @Override
    public String getPodStatus(String namespace, String podName) {
        callCount.incrementAndGet();
        if (raiseNotFound) {
            throw new PodNotFoundException("pod " + namespace + "/" + podName + " not found");
        }
        return podStatus;
    }

    @Override
    public String fetchPodLogs(String namespace, String podName, String container, boolean previous) {
        this.lastLogsContainer = container;
        return logs;
    }

    @Override
    public String fetchInitContainerLogs(String namespace, String podName, String container) {
        return initContainerLogs;
    }

    @Override
    public List<EventInfo> fetchPodEvents(String namespace, String podName) {
        return List.copyOf(events);
    }

    @Override
    public PodSpecInfo fetchPodSpec(Pod podObject) {
        return spec;
    }
}
