package com.brianmehrman.harness.runs;

import com.brianmehrman.harness.execution.Limits;
import java.util.Objects;

public record StartCommand(String commandId, String benchmarkVersion, String profileRevision, Limits limits) {
    public StartCommand {
        if (commandId == null || commandId.isBlank() || commandId.length() > 200 ||
                commandId.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid start command ID");
        if (benchmarkVersion == null || benchmarkVersion.isBlank() ||
                profileRevision == null || profileRevision.isBlank())
            throw new IllegalArgumentException("Benchmark and profile revisions are required");
        Objects.requireNonNull(limits);
    }
}
