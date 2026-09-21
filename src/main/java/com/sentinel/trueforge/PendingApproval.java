package com.sentinel.trueforge;

/**
 * A tool call the TrueForge agent wants to make that is waiting for a human decision.
 *
 * @param sessionId TrueForge session the call belongs to
 * @param threadId  agent thread that owns the call (echoed back when approving)
 * @param toolCallId id of the paused tool call (echoed back when approving)
 * @param connector MCP connector the call targets (e.g. "k8s-prod"), or null if not a connector call
 * @param toolName  the underlying tool (e.g. "kubectl_patch")
 * @param input     pretty-printed tool input, for the human to review
 * @param sessionUrl link to the run in the TrueForge UI
 */
public record PendingApproval(
    String sessionId,
    String threadId,
    String toolCallId,
    String connector,
    String toolName,
    String input,
    String sessionUrl) {}
