package com.brianmehrman.harness.execution;

public record StopCommand(String commandId, long expectedVersion) {
    public StopCommand {
        if (commandId == null || commandId.isBlank() || commandId.length() > 200 ||
                commandId.codePoints().anyMatch(Character::isISOControl) || expectedVersion < 0)
            throw new IllegalArgumentException("Invalid stop command");
    }
}
