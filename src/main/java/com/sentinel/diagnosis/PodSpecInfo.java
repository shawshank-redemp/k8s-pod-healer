package com.sentinel.diagnosis;

import java.util.Map;

public record PodSpecInfo(
    String image,
    String memoryLimit,
    String cpuLimit,
    String memoryRequest,
    String cpuRequest,
    Map<String, String> envVars,
    ProbeInfo livenessProbe,
    ProbeInfo readinessProbe) {

    public static PodSpecInfo unknown() {
        return new PodSpecInfo("unknown", "unknown", "unknown", "unknown", "unknown", Map.of(), null, null);
    }
}
