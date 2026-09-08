package com.ciaozn.alphatrader.strategy.config;

import com.ciaozn.alphatrader.common.model.Interval;
import com.ciaozn.alphatrader.common.model.Symbol;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StrategySpecTest {

    private static final Set<String> BTC = Set.of("BTCUSDT.PERP");

    @Test
    void parsesTheRawConfigurationForm() {
        StrategySpec spec = StrategySpec.of("ma-btc", "ma-cross", true,
                new LinkedHashSet<>(List.of("BTCUSDT.PERP", "ethusdt.perp")), "1h", Map.of("fastPeriod", "10"));

        assertThat(spec.id()).isEqualTo("ma-btc");
        assertThat(spec.type()).isEqualTo("ma-cross");
        assertThat(spec.enabled()).isTrue();
        // unified symbols, configuration order preserved
        assertThat(spec.symbols()).containsExactly(Symbol.parse("BTCUSDT.PERP"), Symbol.parse("ETHUSDT.PERP"));
        assertThat(spec.interval()).isEqualTo(Interval.H1);
        assertThat(spec.params()).containsEntry("fastPeriod", "10");
    }

    @Test
    void defaultsParamsToEmptyAndRejectsMutation() {
        StrategySpec spec = StrategySpec.of("a", "ma-cross", true, BTC, "4h", null);

        assertThat(spec.params()).isEmpty();
        assertThat(spec.interval()).isEqualTo(Interval.H4);
        assertThatThrownBy(() -> spec.params().put("x", "1"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsIncompleteConfiguration() {
        assertThatThrownBy(() -> StrategySpec.of(" ", "ma-cross", true, BTC, "1h", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("id must not be blank");
        assertThatThrownBy(() -> StrategySpec.of("ma-btc", " ", true, BTC, "1h", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("has no type");
        assertThatThrownBy(() -> StrategySpec.of("ma-btc", "ma-cross", true, Set.of(), "1h", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("has no symbols");
        assertThatThrownBy(() -> StrategySpec.of("ma-btc", "ma-cross", true, BTC, "2h", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StrategySpec.of("ma-btc", "ma-cross", true, Set.of("BTC-SPOT"), "1h", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** The id is the clientOrderId prefix, so the exchange charset and length apply (FR-EX-02). */
    @Test
    void rejectsAnIdThatCannotBeUsedAsAnOrderPrefix() {
        assertThatThrownBy(() -> StrategySpec.of("ma cross", "ma-cross", true, BTC, "1h", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1-20 characters");
        assertThatThrownBy(() -> StrategySpec.of("x".repeat(21), "ma-cross", true, BTC, "1h", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1-20 characters");
        assertThat(StrategySpec.of("ma-cross.btc_1h", "ma-cross", true, BTC, "1h", Map.of()).id())
                .isEqualTo("ma-cross.btc_1h");
    }
}
