package com.ciaozn.alphatrader.strategy.indicator;

/**
 * Average True Range with Wilder's smoothing (FR-ST-04).
 *
 * <p>True range needs the previous close, so the first bar contributes {@code high - low}.
 * Seed: simple average of the first {@code period} true ranges, then Wilder's recursion.
 * Used as a volatility filter and as the denominator for ATR-percentage comparisons.
 */
public final class Atr {

    private final int period;
    private double previousClose = Double.NaN;
    private double rangeSum;
    private int ranges;
    private double value = Double.NaN;
    private boolean ready;

    public Atr(int period) {
        if (period < 1) {
            throw new IllegalArgumentException("ATR period must be >= 1, got " + period);
        }
        this.period = period;
    }

    public void update(double high, double low, double close) {
        double trueRange = Double.isNaN(previousClose)
                ? high - low
                : Math.max(high - low, Math.max(Math.abs(high - previousClose), Math.abs(low - previousClose)));
        previousClose = close;

        if (!ready) {
            rangeSum += trueRange;
            ranges++;
            if (ranges == period) {
                value = rangeSum / period;
                ready = true;
            }
            return;
        }
        value = (value * (period - 1) + trueRange) / period;
    }

    public boolean ready() {
        return ready;
    }

    /** @return the current ATR, or NaN while warming up. */
    public double value() {
        return value;
    }

    /** ATR as a percentage of the reference price - the scale-free volatility measure. */
    public double percentOf(double price) {
        return ready && price != 0 ? (value / price) * 100.0 : Double.NaN;
    }

    public int period() {
        return period;
    }

    /** ATR of the last bar in the arrays (all three must have equal length). */
    public static double of(double[] highs, double[] lows, double[] closes, int period) {
        if (highs.length != lows.length || highs.length != closes.length) {
            throw new IllegalArgumentException("high/low/close arrays must have equal length");
        }
        if (highs.length < period) {
            return Double.NaN;
        }
        Atr atr = new Atr(period);
        for (int i = 0; i < highs.length; i++) {
            atr.update(highs[i], lows[i], closes[i]);
        }
        return atr.value();
    }
}
