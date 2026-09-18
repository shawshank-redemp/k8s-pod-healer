package com.sentinel.diagnosis;

import com.sentinel.detection.PodInfo;
import java.util.List;

/** Interface for sending diagnostic context to Claude and getting a structured analysis back. */
public interface ClaudeAnalyzer {
    ClaudeAnalysis analyze(PodInfo podInfo, String logs, List<EventInfo> events, PodSpecInfo spec);
}
