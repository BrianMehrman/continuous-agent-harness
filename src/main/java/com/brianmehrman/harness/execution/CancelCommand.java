package com.brianmehrman.harness.execution;

public record CancelCommand(String commandId, long expectedVersion) {
    public CancelCommand {
        if (commandId == null || commandId.isBlank() || commandId.length() > 200 ||
                commandId.codePoints().anyMatch(Character::isISOControl) || expectedVersion < 0)
            throw new IllegalArgumentException("Invalid cancellation command");
    }
}
