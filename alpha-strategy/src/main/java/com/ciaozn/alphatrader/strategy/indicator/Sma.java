package com.ciaozn.alphatrader.strategy.indicator;

/**
 * Simple moving average, streaming (FR-ST-04).
 *
 * <p>The average is recomputed from the retained window on every read instead of keeping a
 * running sum: incremental sums drift after tens of thousands of updates, and drift would
 * make a long backtest diverge from a short live run over the same bars (NFR-04, SC-05).
 * Window sizes here are small (tens to a few hundred), so the cost is irrelevant.
 *
 * <p>Double arithmetic is used for indicator math (never for money): Java's FP is strict
 * IEEE-754, so the same input sequence yields bit-identical results everywhere.
 */
public final class Sma {

    private final int period;
    private final double[] window;
    private int size;
    private int writeIndex;

    public Sma(int period) {
        if (period < 1) {
            throw new IllegalArgumentException("SMA period must be >= 1, got " + period);
        }
        this.period = period;
        this.window = new double[period];
    }

    public void update(double value) {
        window[writeIndex] = value;
        writeIndex = (writeIndex + 1) % period;
        if (size < period) {
            size++;
        }
    }

    /** True once {@code period} values have been seen. */
    public boolean ready() {
        return size == period;
    }

    /** @return the current average, or NaN while warming up. */
    public double value() {
        if (!ready()) {
            return Double.NaN;
        }
        // Chronological (oldest -> newest) so the FP addition order does not depend on
        // where the ring buffer happens to have wrapped.
        double sum = 0;
        for (int i = 0; i < period; i++) {
            sum += window[(writeIndex + i) % period];
        }
        return sum / period;
    }

    public int period() {
        return period;
    }

    /** Average of the last {@code period} entries of {@code values}. */
    public static double of(double[] values, int period) {
        if (values.length < period) {
            return Double.NaN;
        }
        Sma sma = new Sma(period);
        for (int i = values.length - period; i < values.length; i++) {
            sma.update(values[i]);
        }
        return sma.value();
    }
}
