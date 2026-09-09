package com.ciaozn.alphatrader.backtest.report;

import com.ciaozn.alphatrader.common.model.Money;

import java.math.BigDecimal;
import java.util.List;

/**
 * The account's value over time: one point per replayed round, plus the equity the run
 * started from.
 *
 * <p>{@code startingEquity} is kept separate from the points rather than prepended to them
 * because it has no timestamp of its own - it is the value <em>before</em> the first bar.
 * {@link PerformanceAnalyzer} materializes it at {@code points[0].businessTs - period} when a
 * metric needs it (the first return, the first drawdown peak), so the curve itself never
 * carries an invented time.
 *
 * <p>Points are strictly one per distinct business time. Two series on the same interval close
 * at the same instant and therefore produce two rounds with the same timestamp; only the last
 * sample at that instant is kept, because equity <em>as of</em> a moment means "after everything
 * at that moment has been processed". Keeping both would insert a zero-length period into the
 * return series and deflate every volatility-based metric.
 */
public record EquityCurve(BigDecimal startingEquity, List<Point> points) {

    /** Account value at one business instant. */
    public record Point(long businessTs, BigDecimal equity) {
    }

    public EquityCurve {
        if (startingEquity.signum() <= 0) {
            throw new IllegalArgumentException(
                    "startingEquity must be > 0 to be a return baseline, got " + startingEquity);
        }
        points = List.copyOf(points);
        long previous = Long.MIN_VALUE;
        for (Point point : points) {
            if (point.businessTs() <= previous) {
                throw new IllegalArgumentException("Equity curve points must ascend by business time and be"
                        + " distinct: " + point.businessTs() + " follows " + previous);
            }
            previous = point.businessTs();
        }
    }

    public boolean isEmpty() {
        return points.isEmpty();
    }

    /** Equity at the end of the run, or the starting equity if nothing was ever sampled. */
    public BigDecimal finalEquity() {
        return points.isEmpty() ? startingEquity : points.get(points.size() - 1).equity();
    }

    /** Total return over the whole run, exactly: {@code finalEquity / startingEquity - 1}. */
    public BigDecimal totalReturn() {
        return Money.divide(finalEquity(), startingEquity).subtract(BigDecimal.ONE);
    }
}
