package com.sentinel.diagnosis;

import java.util.List;

public record ClaudeAnalysis(
    String rootCause, String severity, String recommendedFix, double confidence, List<String> evidence) {}
