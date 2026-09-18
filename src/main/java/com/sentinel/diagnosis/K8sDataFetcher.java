package com.sentinel.diagnosis;

import io.fabric8.kubernetes.api.model.Pod;
import java.util.List;

/** Interface for fetching pod data from Kubernetes. */
public interface K8sDataFetcher {

    /**
     * Return the pod's current phase string. Throw PodNotFoundException (or let any other
     * exception propagate) on lookup failure so the caller can distinguish "deleted/unreachable"
     * from a normal phase value.
     */
    String getPodStatus(String namespace, String podName);

    /** Return logs for {@code container} (primary container if null). */
    String fetchPodLogs(String namespace, String podName, String container, boolean previous);

    /** Return logs for a specific init container, or null if unavailable. */
    String fetchInitContainerLogs(String namespace, String podName, String container);

    List<EventInfo> fetchPodEvents(String namespace, String podName);

    PodSpecInfo fetchPodSpec(Pod podObject);
}
