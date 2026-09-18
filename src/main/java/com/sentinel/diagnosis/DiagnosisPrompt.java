package com.sentinel.diagnosis;

import com.sentinel.detection.PodInfo;
import java.util.List;
import java.util.stream.Collectors;

/** Builds the exact prompt Claude sees. Used by RealClaudeClient; kept standalone so it's easy
 * to unit test or reuse. */
public final class DiagnosisPrompt {

    private DiagnosisPrompt() {}

    private static String formatEvents(List<EventInfo> events) {
        if (events.isEmpty()) return "[No events]";
        return events.stream()
            .map(e -> e.timestamp() != null
                ? "- [" + e.timestamp() + "] " + e.reason() + ": " + e.message()
                : "- " + e.reason() + ": " + e.message())
            .collect(Collectors.joining("\n"));
    }

    private static String formatProbe(ProbeInfo probe) {
        return "{initialDelaySeconds=" + probe.initialDelaySeconds()
            + ", periodSeconds=" + probe.periodSeconds()
            + ", timeoutSeconds=" + probe.timeoutSeconds()
            + ", failureThreshold=" + probe.failureThreshold() + "}";
    }

    public static String build(PodInfo podInfo, String logs, List<EventInfo> events, PodSpecInfo spec) {
        String envStr = spec.envVars().isEmpty()
            ? "none"
            : spec.envVars().entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(", "));

        StringBuilder probes = new StringBuilder();
        if (spec.livenessProbe() != null) probes.append("liveness=").append(formatProbe(spec.livenessProbe()));
        if (spec.readinessProbe() != null) {
            if (probes.length() > 0) probes.append("; ");
            probes.append("readiness=").append(formatProbe(spec.readinessProbe()));
        }
        String probesStr = probes.length() > 0 ? probes.toString() : "none configured";

        return """
            You are a Kubernetes troubleshooting expert.

            POD: %s in %s
            Status: %s
            Reason: %s
            Severity: %s
            Restarts: %d

            EVENTS:
            %s

            LOGS:
            %s

            SPEC:
            - Image: %s
            - Memory limit: %s
            - CPU limit: %s
            - Environment: %s
            - Probes: %s

            Provide diagnosis as JSON:
            {
              "root_cause": "specific reason pod is failing",
              "severity": "CRITICAL|HIGH|MEDIUM|LOW",
              "recommended_fix": "how to fix it",
              "confidence": 0.0-1.0,
              "evidence": ["key evidence line 1", "key evidence line 2"]
            }"""
            .formatted(
                podInfo.podName(), podInfo.namespace(),
                podInfo.status(), podInfo.reason(), podInfo.severity(), podInfo.restartCount(),
                formatEvents(events),
                logs,
                spec.image(), spec.memoryLimit(), spec.cpuLimit(), envStr, probesStr);
    }
}
