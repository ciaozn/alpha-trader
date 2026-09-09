package com.ciaozn.alphatrader.common;

import com.ciaozn.alphatrader.common.execution.ClientOrderIds;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * clientOrderId is the idempotency key the exchange deduplicates on (FR-EX-02), so its shape is
 * a contract, not a detail: same inputs must give the same string, and an illegal string must
 * fail here rather than as a rejected order in live trading.
 */
class ClientOrderIdsTest {

    private static final long TS = 1_700_000_000_000L;

    @Test
    void combinesStrategyTimestampAndSequence() {
        assertThat(ClientOrderIds.of("ma-cross-btc", TS, 1))
                .isEqualTo("ma-cross-btc-" + Long.toString(TS, 36) + "-1");
    }

    @Test
    void staysWithinTheExchangeLimitForAMaximumLengthStrategyId() {
        String id = ClientOrderIds.of("a".repeat(20), TS, 999_999);

        assertThat(id).hasSizeLessThanOrEqualTo(ClientOrderIds.MAX_LENGTH);
        assertThat(id).matches("[.A-Za-z0-9:/_-]{1,36}");
    }

    @Test
    void isDeterministicAndUnique() {
        assertThat(ClientOrderIds.of("s", TS, 7)).isEqualTo(ClientOrderIds.of("s", TS, 7));
        assertThat(ClientOrderIds.of("s", TS, 7)).isNotEqualTo(ClientOrderIds.of("s", TS, 8));
        assertThat(ClientOrderIds.of("s", TS, 7)).isNotEqualTo(ClientOrderIds.of("s", TS + 1, 7));
        assertThat(ClientOrderIds.of("s", TS, 7)).isNotEqualTo(ClientOrderIds.of("t", TS, 7));
    }

    @Test
    void rejectsAStrategyIdThatCannotBeAnOrderId() {
        assertThatThrownBy(() -> ClientOrderIds.of(null, TS, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("strategyId");
        assertThatThrownBy(() -> ClientOrderIds.of("  ", TS, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("strategyId");
        assertThatThrownBy(() -> ClientOrderIds.of("bad id!", TS, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not usable");
        assertThatThrownBy(() -> ClientOrderIds.of("a".repeat(40), TS, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("shorten the strategy id");
    }

    @Test
    void rejectsNegativeTimestampsAndSequences() {
        assertThatThrownBy(() -> ClientOrderIds.of("s", -1, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(">= 0");
        assertThatThrownBy(() -> ClientOrderIds.of("s", TS, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(">= 0");
    }
}
