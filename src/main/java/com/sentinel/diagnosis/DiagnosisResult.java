package com.sentinel.diagnosis;

import java.util.List;

/** Final output of DiagnosisModule.diagnose() - the thing that would ship off to TrueForge. */
public record DiagnosisResult(
    String podId,
    String podName,
    String namespace,
    String rootCause,
    String severity,
    String recommendedFix,
    double confidence,
    List<String> evidence,
    long analysisTimestamp,
    String rawLogs,
    List<EventInfo> rawEvents) {}
