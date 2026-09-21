package com.sentinel.trueforge;

import java.io.Console;

/**
 * Asks the human at the terminal to approve a paused tool call.
 *
 * <p>Anything other than an explicit "y" denies (pressing Enter denies), and "u" leaves the
 * decision to the TrueForge UI. With no interactive terminal (e.g. run under a service manager)
 * it defers to the UI rather than guessing.
 */
public class ConsoleApprovalHandler implements ApprovalHandler {

    // One prompt at a time, even if several agent runs pause at once.
    private static final Object PROMPT_LOCK = new Object();

    private final Console console;

    public ConsoleApprovalHandler() {
        this(System.console());
    }

    ConsoleApprovalHandler(Console console) {
        this.console = console;
    }

    public boolean isInteractive() {
        return console != null;
    }

    @Override
    public Decision decide(PendingApproval a) {
        synchronized (PROMPT_LOCK) {
            System.out.println();
            System.out.println("======================= APPROVAL REQUIRED =======================");
            System.out.println("The Sentinel agent wants to change PRODUCTION and is paused until you decide.");
            System.out.println("  connector : " + (a.connector() != null ? a.connector() : "(n/a)"));
            System.out.println("  tool      : " + a.toolName());
            System.out.println("  input     : " + a.input());
            System.out.println("  run       : " + a.sessionUrl());
            System.out.println("=================================================================");

            if (console == null) {
                System.out.println("No interactive terminal: approve or deny this in the TrueForge UI (link above).");
                return Decision.defer();
            }

            String answer = console.readLine("Approve this production change? [y = approve / N = deny / u = decide in UI]: ");
            if (answer == null) return Decision.deny("no answer (input closed)");
            switch (answer.trim().toLowerCase()) {
                case "y", "yes":
                    return Decision.allow();
                case "u", "ui":
                    return Decision.defer();
                default:
                    return Decision.deny("denied by operator at the terminal");
            }
        }
    }
}
