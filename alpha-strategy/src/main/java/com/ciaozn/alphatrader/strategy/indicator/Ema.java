package com.ciaozn.alphatrader.strategy.indicator;

/**
 * Exponential moving average, streaming (FR-ST-04).
 *
 * <p>Seeding is explicit and fixed: the first {@code period} values are averaged with an SMA
 * and that average becomes the first EMA; subsequent values use the standard recursion with
 * multiplier {@code 2 / (period + 1)}. Pinning the seed rule is what keeps backtest and live
 * numbers identical - an implementation that seeds differently produces a different series.
 */
public final class Ema {

    private final int period;
    private final double multiplier;
    private final Sma seed;
    private double value = Double.NaN;
    private boolean ready;

    public Ema(int period) {
        if (period < 1) {
            throw new IllegalArgumentException("EMA period must be >= 1, got " + period);
        }
        this.period = period;
        this.multiplier = 2.0 / (period + 1.0);
        this.seed = new Sma(period);
    }

    public void update(double newValue) {
        if (!ready) {
            seed.update(newValue);
            if (seed.ready()) {
                value = seed.value();
                ready = true;
            }
            return;
        }
        value = (newValue - value) * multiplier + value;
    }

    public boolean ready() {
        return ready;
    }

    /** @return the current EMA, or NaN while warming up. */
    public double value() {
        return value;
    }

    public int period() {
        return period;
    }

    /** EMA over the whole array, returning the latest value (NaN if too short). */
    public static double of(double[] values, int period) {
        if (values.length < period) {
            return Double.NaN;
        }
        Ema ema = new Ema(period);
        for (double value : values) {
            ema.update(value);
        }
        return ema.value();
    }
}
