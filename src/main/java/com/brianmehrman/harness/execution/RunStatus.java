package com.brianmehrman.harness.execution;

import java.util.Set;

/** The one list of terminal run statuses. */
public final class RunStatus {
    public static final Set<String> TERMINAL = Set.of("SUCCEEDED", "READY_FOR_REVIEW", "FAILED", "CANCELLED",
            "EXPIRED", "LIMIT_EXHAUSTED", "INFRASTRUCTURE_FAILED", "CONTEXT_OVERFLOW", "STALLED");
    private RunStatus() {}
    public static boolean isTerminal(String status) { return TERMINAL.contains(status); }
}
