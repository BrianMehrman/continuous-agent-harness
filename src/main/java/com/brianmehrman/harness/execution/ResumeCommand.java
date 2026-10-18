package com.brianmehrman.harness.execution;

public record ResumeCommand(String commandId, long expectedVersion, int extraTurns, int extraToolCalls,
        int extraMinutes, String hint) {
    public ResumeCommand {
        if (commandId == null || commandId.isBlank() || commandId.length() > 200 ||
                commandId.codePoints().anyMatch(Character::isISOControl) || expectedVersion < 0 ||
                extraTurns < 0 || extraToolCalls < 0 || extraMinutes < 0)
            throw new IllegalArgumentException("Invalid resume command");
        if (hint != null && (hint.isBlank() || hint.length() > 2000 ||
                hint.codePoints().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\t')))
            throw new IllegalArgumentException("Invalid hint");
    }
}
