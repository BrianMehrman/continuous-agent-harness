package com.brianmehrman.harness.execution;

import com.fasterxml.jackson.annotation.JsonInclude;

public record ResumeCommand(String commandId, long expectedVersion, int extraTurns, int extraToolCalls,
        int extraMinutes, String hint, @JsonInclude(JsonInclude.Include.NON_DEFAULT) long extraCostMicros) {
    public ResumeCommand(String commandId, long expectedVersion, int extraTurns, int extraToolCalls,
            int extraMinutes, String hint) {
        this(commandId, expectedVersion, extraTurns, extraToolCalls, extraMinutes, hint, 0);
    }

    public ResumeCommand {
        if (commandId == null || commandId.isBlank() || commandId.length() > 200 ||
                commandId.codePoints().anyMatch(Character::isISOControl) || expectedVersion < 0 ||
                extraTurns < 0 || extraToolCalls < 0 || extraMinutes < 0 || extraCostMicros < 0)
            throw new IllegalArgumentException("Invalid resume command");
        if (hint != null && (hint.isBlank() || hint.length() > 2000 ||
                hint.codePoints().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\t')))
            throw new IllegalArgumentException("Invalid hint");
    }
}
