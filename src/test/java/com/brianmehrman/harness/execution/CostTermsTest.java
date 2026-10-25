package com.brianmehrman.harness.execution;

import static org.junit.jupiter.api.Assertions.*;

import com.brianmehrman.harness.model.ModelReply;
import com.brianmehrman.harness.model.Prices;
import java.util.List;
import org.junit.jupiter.api.Test;

class CostTermsTest {
    private static final Prices HAIKU = new Prices(100000, 500000, 125000, 10000);

    @Test void exactChargeRoundsUp() {
        var terms = new CostTerms(HAIKU, 12288, 4096, 100000L, 10);
        var reply = new ModelReply("", List.of(), 1201L, 80L, "end_turn", 600L, 0L);

        assertEquals(new CostTerms.Charge(236, false), terms.charge(reply));
    }

    @Test void pricedReplyWithoutUsageIsChargedWorstCase() {
        var terms = new CostTerms(HAIKU, 12288, 4096, 100000L, 10);
        var reply = new ModelReply("", List.of(), null, null, "end_turn");

        assertEquals(new CostTerms.Charge(3277, true), terms.charge(reply));
        assertEquals(new CostTerms.Charge(3277, true), terms.worstCase());
    }

    @Test void freeProfileChargesNothing() {
        var terms = new CostTerms(Prices.FREE, 12288, 4096, null, 10);

        assertEquals(new CostTerms.Charge(0, false), terms.worstCase());
        assertEquals(new CostTerms.Charge(0, false), terms.charge(
                new ModelReply("", List.of(), null, null, "end_turn")));
    }

    @Test void effectiveLimitAppliesBuffer() {
        assertEquals(90000, new CostTerms(HAIKU, 12288, 4096, 100000L, 10).effectiveLimit(100000));
        assertEquals(100000, new CostTerms(HAIKU, 12288, 4096, 100000L, 0).effectiveLimit(100000));
    }
}
