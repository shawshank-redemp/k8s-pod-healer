package com.sentinel.diagnosis;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public final class LogUtils {

    // NOTE: the original spec disagreed with itself on the truncation trigger size
    // ("truncate to last 3000 chars if > 10KB" in the data-collection section vs. "logs > 10MB"
    // in the loopholes section). 10KB is used here since it's the threshold actually reachable
    // by real pod logs in a diagnosis window; 10MB would almost never trigger truncation.
    public static final int LOG_TRUNCATE_CHARS = 3000;
    public static final int LOG_SIZE_THRESHOLD_CHARS = 10_000;

    private static final List<String> ERROR_KEYWORDS =
        List.of("error", "exception", "panic", "fatal", "oom", "killed", "crash");

    private LogUtils() {}

    /**
     * Loophole fix: huge logs get truncated to the tail, but error lines found anywhere in the
     * log (even outside the tail window) are preserved up front so Claude doesn't lose the
     * actual failure signal.
     */
    public static String truncate(String logs) {
        return truncate(logs, LOG_TRUNCATE_CHARS, LOG_SIZE_THRESHOLD_CHARS);
    }

    public static String truncate(String logs, int maxChars, int thresholdChars) {
        if (logs == null || logs.isEmpty()) return "[No logs available]";
        if (logs.length() <= thresholdChars) return logs;

        String tail = logs.substring(logs.length() - maxChars);
        List<String> errorLines = Arrays.stream(logs.split("\n"))
            .filter(line -> ERROR_KEYWORDS.stream().anyMatch(kw -> line.toLowerCase().contains(kw)))
            .collect(Collectors.toList());

        String header = "[Log truncated - " + logs.length() + " chars total, showing last " + maxChars + "]";
        if (!errorLines.isEmpty()) {
            String errorBlock = errorLines.stream()
                .skip(Math.max(0, errorLines.size() - 10))
                .collect(Collectors.joining("\n"));
            return header + "\n\n--- ERROR LINES (extracted) ---\n" + errorBlock + "\n\n--- TAIL ---\n" + tail;
        }
        return header + "\n\n" + tail;
    }
}
