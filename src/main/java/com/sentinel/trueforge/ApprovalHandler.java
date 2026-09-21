package com.sentinel.trueforge;

/**
 * Decides what to do with a tool call the agent has paused on. There is deliberately NO
 * auto-approve implementation: the approval gate exists so a human makes this call.
 */
public interface ApprovalHandler {

    /** ALLOW / DENY carry a human decision; DEFER means "not decided here - a human will resume
     * the run in the TrueForge UI", after which the trigger just follows the resumed run. */
    enum Verdict { ALLOW, DENY, DEFER }

    record Decision(Verdict verdict, String reason) {
        public static Decision allow() { return new Decision(Verdict.ALLOW, null); }
        public static Decision deny(String reason) { return new Decision(Verdict.DENY, reason); }
        public static Decision defer() { return new Decision(Verdict.DEFER, null); }
    }

    Decision decide(PendingApproval approval);
}
