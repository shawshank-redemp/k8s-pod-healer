package com.sentinel.diagnosis;

import com.sentinel.detection.PodInfo;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Canned Claude responses, configurable per test scenario. */
public class MockClaudeClient implements ClaudeAnalyzer {

    private final ClaudeAnalysis response;
    private final boolean raiseError;
    public final AtomicInteger callCount = new AtomicInteger(0);

    public MockClaudeClient() {
        this(null, false);
    }

    public MockClaudeClient(ClaudeAnalysis response) {
        this(response, false);
    }

    public MockClaudeClient(ClaudeAnalysis response, boolean raiseError) {
        this.response = response;
        this.raiseError = raiseError;
    }

    public static MockClaudeClient thatFails() {
        return new MockClaudeClient(null, true);
    }

    @Override
    public ClaudeAnalysis analyze(PodInfo podInfo, String logs, List<EventInfo> events, PodSpecInfo spec) {
        callCount.incrementAndGet();
        if (raiseError) {
            throw new RuntimeException("Mock Claude API failure");
        }
        if (response != null) {
            return response;
        }
        return new ClaudeAnalysis(
            "OOMKilled",
            "CRITICAL",
            "[MockClaude] Investigate manually",
            0.95,
            List.of("[MockClaude] default canned response"));
    }
}
