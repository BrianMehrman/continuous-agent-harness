package com.brianmehrman.harness.model;

public record Prices(long inputMicrosPerMTok, long outputMicrosPerMTok,
        long cacheWriteMicrosPerMTok, long cacheReadMicrosPerMTok) {
    public static final Prices FREE = new Prices(0, 0, 0, 0);

    public Prices {
        if (inputMicrosPerMTok < 0 || outputMicrosPerMTok < 0 ||
                cacheWriteMicrosPerMTok < 0 || cacheReadMicrosPerMTok < 0)
            throw new IllegalArgumentException("Prices must be nonnegative");
    }

    public boolean free() {
        return inputMicrosPerMTok == 0 && outputMicrosPerMTok == 0 &&
                cacheWriteMicrosPerMTok == 0 && cacheReadMicrosPerMTok == 0;
    }
}
