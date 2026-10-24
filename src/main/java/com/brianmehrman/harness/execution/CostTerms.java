package com.brianmehrman.harness.execution;

import com.brianmehrman.harness.model.Prices;

public record CostTerms(Prices prices, long worstCaseInputTokens, long worstCaseOutputTokens,
        Long limitMicros, int bufferPercent) {}
