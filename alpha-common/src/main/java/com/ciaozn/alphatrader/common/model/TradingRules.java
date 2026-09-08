package com.ciaozn.alphatrader.common.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * Exchange-side precision constraints for one symbol (FR-GW-03): tick size, quantity step
 * and minimum notional. In live/paper these are fetched from exchangeInfo and cached; in
 * backtest they come from configuration - the SAME rounding code runs in both modes, which
 * is what keeps order quantities identical between backtest and live (FR-BT-06).
 *
 * <p>Quantity rounding is always downward: an order bigger than intended is a risk breach,
 * an order slightly smaller is merely a missed fraction (spec edge case 4).
 */
public record TradingRules(
        Symbol symbol,
        BigDecimal tickSize,
        BigDecimal stepSize,
        BigDecimal minNotional) {

    public TradingRules {
        requirePositive(tickSize, "tickSize");
        requirePositive(stepSize, "stepSize");
        requireNonNegative(minNotional, "minNotional");
    }

    private static void requirePositive(BigDecimal value, String name) {
        if (value == null || value.signum() <= 0) {
            throw new IllegalArgumentException(name + " must be > 0");
        }
    }

    private static void requireNonNegative(BigDecimal value, String name) {
        if (value == null || value.signum() < 0) {
            throw new IllegalArgumentException(name + " must be >= 0");
        }
    }

    /** Largest multiple of stepSize that is <= qty. */
    public BigDecimal floorQty(BigDecimal qty) {
        if (qty.signum() <= 0) {
            return BigDecimal.ZERO.setScale(stepSize.scale(), RoundingMode.UNNECESSARY);
        }
        return qty.divide(stepSize, 0, RoundingMode.FLOOR)
                .multiply(stepSize)
                .setScale(stepSize.scale(), RoundingMode.UNNECESSARY);
    }

    /** Price snapped to tickSize. Use FLOOR for buys, CEILING for sells, HALF_UP for marks. */
    public BigDecimal alignPrice(BigDecimal price, RoundingMode mode) {
        return price.divide(tickSize, 0, mode)
                .multiply(tickSize)
                .setScale(tickSize.scale(), RoundingMode.UNNECESSARY);
    }

    public boolean isValidQty(BigDecimal qty) {
        return qty.signum() > 0 && floorQty(qty).compareTo(qty) == 0;
    }

    /** True when qty at price reaches the exchange minimum notional (spec edge case 4). */
    public boolean meetsMinNotional(BigDecimal qty, BigDecimal price) {
        return qty.multiply(price).compareTo(minNotional) >= 0;
    }

    /**
     * Rounds a raw quantity down to stepSize, empty when the result is zero or below
     * minNotional - the caller must then skip the order and alert instead of sending a
     * dirty order the exchange would reject.
     */
    public Optional<BigDecimal> tradableQty(BigDecimal rawQty, BigDecimal price) {
        BigDecimal stepped = floorQty(rawQty);
        if (stepped.signum() <= 0 || !meetsMinNotional(stepped, price)) {
            return Optional.empty();
        }
        return Optional.of(stepped);
    }
}
