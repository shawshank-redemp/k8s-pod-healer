package com.sentinel.detection;

import io.fabric8.kubernetes.api.model.Pod;

/** Safely-extracted, downstream-ready summary of a pod (see DetectionModule.extractPodInfo). */
public record PodInfo(
    String podId,
    String podName,
    String namespace,
    String status,
    String reason,
    int restartCount,
    String severity,
    Pod podObject) {}
