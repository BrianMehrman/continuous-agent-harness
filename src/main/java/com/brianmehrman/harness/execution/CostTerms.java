package com.brianmehrman.harness.execution;

import com.brianmehrman.harness.model.Prices;
import com.brianmehrman.harness.model.ModelReply;
import java.math.BigInteger;

public record CostTerms(Prices prices, long worstCaseInputTokens, long worstCaseOutputTokens,
        Long limitMicros, int bufferPercent) {
    private static final BigInteger MILLION = BigInteger.valueOf(1_000_000);

    public record Charge(long micros, boolean estimated) {
        public Charge {
            if (micros < 0) throw new IllegalArgumentException("Negative model charge");
        }
    }

    public CostTerms {
        if (prices == null || worstCaseInputTokens < 0 || worstCaseOutputTokens < 0 ||
                bufferPercent < 0 || bufferPercent > 50 || limitMicros != null && limitMicros <= 0)
            throw new IllegalArgumentException("Invalid cost terms");
    }

    public Charge charge(ModelReply reply) {
        if (prices.free()) return new Charge(0, false);
        if (reply.inputTokens() == null || reply.outputTokens() == null) return worstCase();
        long input = nonnegative(reply.inputTokens());
        long output = nonnegative(reply.outputTokens());
        long cacheWrite = nonnegative(reply.cacheWriteTokens() == null ? 0 : reply.cacheWriteTokens());
        long cacheRead = nonnegative(reply.cacheReadTokens() == null ? 0 : reply.cacheReadTokens());
        return new Charge(micros(input, output, cacheWrite, cacheRead), false);
    }

    public Charge worstCase() {
        if (prices.free()) return new Charge(0, false);
        return new Charge(micros(worstCaseInputTokens, worstCaseOutputTokens, 0, 0), true);
    }

    public long effectiveLimit(long limit) {
        if (limit < 0) throw new IllegalArgumentException("Negative cost limit");
        return BigInteger.valueOf(limit).multiply(BigInteger.valueOf(100 - bufferPercent))
                .divide(BigInteger.valueOf(100)).longValueExact();
    }

    private long micros(long input, long output, long cacheWrite, long cacheRead) {
        BigInteger units = product(input, prices.inputMicrosPerMTok())
                .add(product(output, prices.outputMicrosPerMTok()))
                .add(product(cacheWrite, prices.cacheWriteMicrosPerMTok()))
                .add(product(cacheRead, prices.cacheReadMicrosPerMTok()));
        return units.add(MILLION.subtract(BigInteger.ONE)).divide(MILLION).longValueExact();
    }

    private static BigInteger product(long tokens, long rate) {
        return BigInteger.valueOf(tokens).multiply(BigInteger.valueOf(rate));
    }

    private static long nonnegative(long tokens) {
        if (tokens < 0) throw new IllegalArgumentException("Negative model usage");
        return tokens;
    }
}
