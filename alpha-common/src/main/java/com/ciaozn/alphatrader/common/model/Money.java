package com.ciaozn.alphatrader.common.model;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * The single definition of monetary precision (NFR-04, FR-BT-06).
 *
 * <p>Every component that does money math - the portfolio book, the position sizer, the
 * simulated matcher, the live OMS - must round identically, or backtest and live results
 * drift apart and repeated runs stop being bit-identical. So the constants live here and
 * nowhere else: 24 significant digits for intermediate arithmetic, a fixed scale of 8 for
 * stored values (finer than any exchange filter, coarse enough to stay exact in decimal).
 */
public final class Money {

    public static final MathContext MC = new MathContext(24, RoundingMode.HALF_UP);
    public static final int SCALE = 8;

    private Money() {
    }

    /** Normalizes a value to the canonical money scale. */
    public static BigDecimal of(BigDecimal value) {
        return value.setScale(SCALE, RoundingMode.HALF_UP);
    }

    public static BigDecimal of(String value) {
        return new BigDecimal(value).setScale(SCALE, RoundingMode.HALF_UP);
    }

    /** Zero at the canonical scale - so sums of nothing still compare equal to stored values. */
    public static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(SCALE, RoundingMode.UNNECESSARY);
    }

    public static BigDecimal divide(BigDecimal numerator, BigDecimal denominator) {
        return numerator.divide(denominator, MC);
    }
}
