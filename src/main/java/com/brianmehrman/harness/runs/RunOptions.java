package com.brianmehrman.harness.runs;

public record RunOptions(String role, String profileRevision, boolean allowRemote,
        Long costLimitMicros, Integer costBufferPercent) {}
