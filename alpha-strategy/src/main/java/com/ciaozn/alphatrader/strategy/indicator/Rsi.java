package com.ciaozn.alphatrader.strategy.indicator;

/**
 * Relative Strength Index with Wilder's smoothing (FR-ST-04).
 *
 * <p>Seed: simple average of the first {@code period} gains and losses. Then
 * {@code avg = (prevAvg * (period-1) + current) / period}. Output is
 * {@code 100 - 100 / (1 + avgGain / avgLoss)}, i.e. 100 when there were no losses at all.
 * These are the textbook definitions; the acceptance test pins them against an independent
 * reference implementation so a "small improvement" here cannot silently change every
 * backtest result.
 */
public final class Rsi {

    private final int period;
    private double previous = Double.NaN;
    private double gainSum;
    private double lossSum;
    private int changes;
    private double avgGain;
    private double avgLoss;
    private boolean ready;

    public Rsi(int period) {
        if (period < 2) {
            throw new IllegalArgumentException("RSI period must be >= 2, got " + period);
        }
        this.period = period;
    }

    public void update(double price) {
        if (Double.isNaN(previous)) {
            previous = price;
            return;
        }
        double change = price - previous;
        previous = price;
        double gain = Math.max(change, 0.0);
        double loss = Math.max(-change, 0.0);

        if (!ready) {
            gainSum += gain;
            lossSum += loss;
            changes++;
            if (changes == period) {
                avgGain = gainSum / period;
                avgLoss = lossSum / period;
                ready = true;
            }
            return;
        }
        avgGain = (avgGain * (period - 1) + gain) / period;
        avgLoss = (avgLoss * (period - 1) + loss) / period;
    }

    public boolean ready() {
        return ready;
    }

    /** @return RSI in [0, 100], or NaN while warming up. */
    public double value() {
        if (!ready) {
            return Double.NaN;
        }
        if (avgLoss == 0.0) {
            return 100.0;
        }
        double rs = avgGain / avgLoss;
        return 100.0 - (100.0 / (1.0 + rs));
    }

    public int period() {
        return period;
    }

    /** RSI of the last value in the array (NaN if too short: needs period + 1 prices). */
    public static double of(double[] prices, int period) {
        if (prices.length < period + 1) {
            return Double.NaN;
        }
        Rsi rsi = new Rsi(period);
        for (double price : prices) {
            rsi.update(price);
        }
        return rsi.value();
    }
}
