package com.brianmehrman.harness.runs;

import com.brianmehrman.harness.execution.Limits;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Objects;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record StartCommand(String commandId, String benchmarkVersion, String profileRevision, Limits limits,
        String role, Boolean allowRemote, Long costLimitMicros, Integer costBufferPercent) {
    public StartCommand(String commandId, String benchmarkVersion, String profileRevision, Limits limits) {
        this(commandId, benchmarkVersion, profileRevision, limits, null, null, null, null);
    }

    public StartCommand {
        if (commandId == null || commandId.isBlank() || commandId.length() > 200 ||
                commandId.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid start command ID");
        if (benchmarkVersion == null || benchmarkVersion.isBlank() ||
                profileRevision != null && profileRevision.isBlank() || role != null && role.isBlank())
            throw new IllegalArgumentException("Invalid benchmark, profile, or role");
        Objects.requireNonNull(limits);
    }
}
