package com.ciaozn.alphatrader.common;

import com.ciaozn.alphatrader.common.model.Symbol;
import com.ciaozn.alphatrader.common.model.TradingRules;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TradingRulesTest {

    /** Real BTCUSDT perpetual filters: tick 0.10, step 0.001, minNotional 20 USDT. */
    private final TradingRules rules = new TradingRules(
            Symbol.parse("BTCUSDT.PERP"),
            new BigDecimal("0.10"),
            new BigDecimal("0.001"),
            new BigDecimal("20"));

    @Test
    void floorsQuantityDownToStepSize() {
        assertThat(rules.floorQty(new BigDecimal("0.1239")))
                .isEqualByComparingTo(new BigDecimal("0.123"));
        assertThat(rules.floorQty(new BigDecimal("1")))
                .isEqualByComparingTo(new BigDecimal("1.000"));
        assertThat(rules.floorQty(new BigDecimal("0.0009")))
                .isEqualByComparingTo(BigDecimal.ZERO);
        // never rounds up: an oversized order is a risk breach
        assertThat(rules.floorQty(new BigDecimal("0.9999999")))
                .isEqualByComparingTo(new BigDecimal("0.999"));
    }

    @Test
    void alignsPriceToTickSizeInBothDirections() {
        BigDecimal price = new BigDecimal("78546.75");
        assertThat(rules.alignPrice(price, RoundingMode.FLOOR))
                .isEqualByComparingTo(new BigDecimal("78546.70"));
        assertThat(rules.alignPrice(price, RoundingMode.CEILING))
                .isEqualByComparingTo(new BigDecimal("78546.80"));
        assertThat(rules.alignPrice(new BigDecimal("78546.70"), RoundingMode.HALF_UP))
                .isEqualByComparingTo(new BigDecimal("78546.70"));
    }

    @Test
    void keepsScaleCleanForExchangeSubmission() {
        assertThat(rules.floorQty(new BigDecimal("0.1239")).toPlainString()).isEqualTo("0.123");
        assertThat(rules.alignPrice(new BigDecimal("78546.75"), RoundingMode.FLOOR).toPlainString())
                .isEqualTo("78546.70");
    }

    @Test
    void rejectsQuantitiesBelowMinNotional() {
        // 0.0002 BTC @ 78000 = 15.6 USDT < 20 -> must not be sent (spec edge case 4)
        Optional<BigDecimal> tradable = rules.tradableQty(new BigDecimal("0.0002"), new BigDecimal("78000"));
        assertThat(tradable).isEmpty();

        // 0.001 BTC @ 78000 = 78 USDT >= 20 -> tradable, already step-aligned
        assertThat(rules.tradableQty(new BigDecimal("0.001"), new BigDecimal("78000")))
                .contains(new BigDecimal("0.001"));
    }

    @Test
    void detectsAlreadyAlignedQuantities() {
        assertThat(rules.isValidQty(new BigDecimal("0.123"))).isTrue();
        assertThat(rules.isValidQty(new BigDecimal("0.1234"))).isFalse();
        assertThat(rules.isValidQty(BigDecimal.ZERO)).isFalse();
    }

    @Test
    void rejectsNonPositiveFilters() {
        assertThatThrownBy(() -> new TradingRules(Symbol.parse("BTCUSDT.PERP"),
                BigDecimal.ZERO, new BigDecimal("0.001"), new BigDecimal("20")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TradingRules(Symbol.parse("BTCUSDT.PERP"),
                new BigDecimal("0.1"), BigDecimal.ZERO, new BigDecimal("20")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
