package com.ciaozn.alphatrader.backtest.report;

import com.ciaozn.alphatrader.common.model.Money;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Turns an {@link EquityCurve} and a list of round trips into the reported numbers (FR-BT-04).
 * Pure: same inputs, same outputs, no clock, no I/O - which is what lets SC-02 assert that a run
 * is reproducible by comparing metrics rather than by re-reading a file.
 *
 * <p>Conventions, all of them the usual ones so the numbers can be compared with any other
 * backtester:
 * <ul>
 *   <li>returns are simple period returns {@code equity[i] / equity[i-1] - 1}, the first one
 *       taken against the starting equity, so a curve of {@code n} points yields {@code n}
 *       periods;</li>
 *   <li>Sharpe uses a zero risk-free rate and the <em>sample</em> standard deviation
 *       ({@code n-1}), annualized by {@code sqrt(periods per year)};</li>
 *   <li>annualized return is the geometric extrapolation {@code (1 + totalReturn)^(1/years) - 1}
 *       over the calendar time the run actually spans - gaps included, because time passed
 *       whether or not there was data. Sharpe by contrast annualizes by the sampling frequency,
 *       so the two use different notions of time on purpose: one answers "what would this have
 *       earned per year", the other "how much return per unit of risk per year";</li>
 *   <li>a year is 365 days; the fourth is not worth the ambiguity it adds to a period count.</li>
 * </ul>
 *
 * <p>Annualizing a short run extrapolates violently - a 6-hour fixture showing 4% compounds to
 * an absurd figure - so the value is reported as-is and never used as a threshold. Total return
 * and max drawdown, both exact decimals over the window actually replayed, are the numbers to
 * assert on.
 */
public final class PerformanceAnalyzer {

    /** A year is 365 days; see the class note. */
    public static final long MILLIS_PER_YEAR = 365L * 24L * 60L * 60L * 1000L;

    private PerformanceAnalyzer() {
    }

    /**
     * @param trades         every trade, closed and open; win rate and profit factor are
     *                       computed over the closed ones only
     * @param samplingPeriod the nominal spacing of the curve's points - the bar interval for a
     *                       single-series run. Supplies the annualization factor and the time of
     *                       the starting equity, which sits one period before the first point.
     * @throws IllegalArgumentException if the curve has no points at all or the period is not
     *                                  positive. An empty curve is refused rather than reported
     *                                  as a flat, zero-risk run: no data must not look like a
     *                                  strategy that simply never lost money.
     */
    public static PerformanceMetrics analyze(EquityCurve curve, List<Trade> trades, Duration samplingPeriod) {
        if (curve.isEmpty()) {
            throw new IllegalArgumentException(
                    "Cannot measure performance of an empty equity curve - nothing was sampled");
        }
        long periodMillis = samplingPeriod.toMillis();
        if (periodMillis <= 0) {
            throw new IllegalArgumentException("samplingPeriod must be positive: " + samplingPeriod);
        }

        List<EquityCurve.Point> points = curve.points();
        int periods = points.size();
        BigDecimal startingEquity = curve.startingEquity();
        BigDecimal finalEquity = points.get(periods - 1).equity();
        BigDecimal totalReturn = Money.of(curve.totalReturn());

        long firstTs = points.get(0).businessTs();
        long lastTs = points.get(periods - 1).businessTs();
        // n points cover n periods: the first one runs from (firstTs - period) to firstTs.
        double yearsElapsed = ((lastTs - firstTs) + periodMillis) / (double) MILLIS_PER_YEAR;

        boolean allPositive = true;
        for (EquityCurve.Point point : points) {
            if (point.equity().signum() <= 0) {
                allPositive = false;
                break;
            }
        }

        OptionalDouble annualized = annualizedReturn(totalReturn, yearsElapsed);
        OptionalDouble sharpe = allPositive ? sharpe(points, startingEquity, periodMillis) : OptionalDouble.empty();
        Drawdowns drawdowns = worstDrawdown(curve, points, firstTs, periodMillis);

        TradeStats stats = tradeStats(trades);

        return new PerformanceMetrics(startingEquity, finalEquity,
                Money.of(finalEquity.subtract(startingEquity)), totalReturn,
                periods, yearsElapsed, annualized, sharpe,
                drawdowns.depth(), drawdowns.episode(),
                stats.closed(), stats.open(), stats.wins(), stats.losses(), stats.winRate(),
                stats.grossProfit(), stats.grossLoss(), stats.profitFactor(),
                stats.averageWin(), stats.averageLoss(), stats.payoffRatio(),
                stats.expectancy(), stats.best(), stats.worst(), stats.fees());
    }

    private static OptionalDouble annualizedReturn(BigDecimal totalReturn, double yearsElapsed) {
        double growth = 1.0 + totalReturn.doubleValue();
        // A run that lost everything (or more) has no geometric rate: any exponent of a zero or
        // negative base is either zero or not a real number.
        if (growth <= 0) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(Math.pow(growth, 1.0 / yearsElapsed) - 1.0);
    }

    private static OptionalDouble sharpe(List<EquityCurve.Point> points, BigDecimal startingEquity,
                                         long periodMillis) {
        int n = points.size();
        if (n < 2) {
            // One period has no dispersion, so there is no risk to adjust the return by.
            return OptionalDouble.empty();
        }
        double[] returns = new double[n];
        returns[0] = ratio(points.get(0).equity(), startingEquity) - 1.0;
        for (int i = 1; i < n; i++) {
            returns[i] = ratio(points.get(i).equity(), points.get(i - 1).equity()) - 1.0;
        }
        double sum = 0.0;
        for (double r : returns) {
            sum += r;
        }
        double mean = sum / n;
        double squaredDeviations = 0.0;
        for (double r : returns) {
            double deviation = r - mean;
            squaredDeviations += deviation * deviation;
        }
        double std = Math.sqrt(squaredDeviations / (n - 1));
        if (std == 0.0) {
            // A perfectly flat curve: zero risk, so the ratio is undefined rather than infinite.
            return OptionalDouble.empty();
        }
        double periodsPerYear = MILLIS_PER_YEAR / (double) periodMillis;
        return OptionalDouble.of((mean / std) * Math.sqrt(periodsPerYear));
    }

    private static double ratio(BigDecimal numerator, BigDecimal denominator) {
        return numerator.doubleValue() / denominator.doubleValue();
    }

    private record Drawdowns(BigDecimal depth, Optional<PerformanceMetrics.Drawdown> episode) {
    }

    private static Drawdowns worstDrawdown(EquityCurve curve, List<EquityCurve.Point> points,
                                           long firstTs, long periodMillis) {
        BigDecimal peak = curve.startingEquity();
        long peakTs = firstTs - periodMillis;
        BigDecimal maxDrawdown = Money.zero();
        PerformanceMetrics.Drawdown worst = null;
        for (EquityCurve.Point point : points) {
            if (point.equity().compareTo(peak) > 0) {
                peak = point.equity();
                peakTs = point.businessTs();
            }
            // peak is the starting equity or a strictly larger sample, so it is positive by
            // construction: a blown account is a >=100% drawdown, never a division by zero.
            BigDecimal depth = Money.of(Money.divide(peak.subtract(point.equity()), peak));
            if (depth.compareTo(maxDrawdown) > 0) {
                maxDrawdown = depth;
                worst = new PerformanceMetrics.Drawdown(peakTs, point.businessTs(), depth);
            }
        }
        return new Drawdowns(maxDrawdown, Optional.ofNullable(worst));
    }

    private record TradeStats(int closed, int open, int wins, int losses, OptionalDouble winRate,
                              BigDecimal grossProfit, BigDecimal grossLoss,
                              Optional<BigDecimal> profitFactor, BigDecimal averageWin,
                              BigDecimal averageLoss, Optional<BigDecimal> payoffRatio,
                              BigDecimal expectancy, BigDecimal best, BigDecimal worst,
                              BigDecimal fees) {
    }

    private static TradeStats tradeStats(List<Trade> trades) {
        int closed = 0;
        int open = 0;
        int wins = 0;
        int losses = 0;
        BigDecimal grossProfit = Money.zero();
        BigDecimal grossLoss = Money.zero();
        BigDecimal netTotal = Money.zero();
        // Seeded from the first closed trade rather than from zero: a run in which every trade
        // lost has a best trade equal to its least bad one, and reporting 0.00 there would read
        // as "one trade broke even".
        BigDecimal best = null;
        BigDecimal worst = null;
        BigDecimal fees = Money.zero();
        for (Trade trade : trades) {
            fees = fees.add(trade.fees());
            if (trade.open()) {
                open++;
                continue;
            }
            closed++;
            BigDecimal net = trade.netPnl();
            netTotal = netTotal.add(net);
            if (net.signum() > 0) {
                wins++;
                grossProfit = grossProfit.add(net);
            } else if (net.signum() < 0) {
                losses++;
                grossLoss = grossLoss.add(net.negate());
            }
            if (best == null || net.compareTo(best) > 0) {
                best = net;
            }
            if (worst == null || net.compareTo(worst) < 0) {
                worst = net;
            }
        }

        OptionalDouble winRate = closed == 0
                ? OptionalDouble.empty()
                : OptionalDouble.of(wins / (double) closed);
        // No losing trade means the factor is unbounded, not zero and not one: the honest answer
        // is "undefined", which the report renders as such instead of printing a number that
        // could be compared against a threshold.
        Optional<BigDecimal> profitFactor = grossLoss.signum() == 0
                ? Optional.empty()
                : Optional.of(Money.of(Money.divide(grossProfit, grossLoss)));
        BigDecimal averageWin = wins == 0
                ? Money.zero()
                : Money.of(Money.divide(grossProfit, BigDecimal.valueOf(wins)));
        BigDecimal averageLoss = losses == 0
                ? Money.zero()
                : Money.of(Money.divide(grossLoss, BigDecimal.valueOf(losses)));
        // Guarded on the counts, not on averageLoss: that zero is a fallback, and dividing by it
        // would report a payoff of 0.00, which reads as "wins are zero-sized" rather than "there
        // were no wins".
        Optional<BigDecimal> payoffRatio = wins == 0 || losses == 0
                ? Optional.empty()
                : Optional.of(Money.of(Money.divide(averageWin, averageLoss)));
        BigDecimal expectancy = closed == 0
                ? Money.zero()
                : Money.of(Money.divide(netTotal, BigDecimal.valueOf(closed)));

        return new TradeStats(closed, open, wins, losses, winRate, Money.of(grossProfit),
                Money.of(grossLoss), profitFactor, averageWin, averageLoss, payoffRatio,
                expectancy, best == null ? Money.zero() : Money.of(best),
                worst == null ? Money.zero() : Money.of(worst), Money.of(fees));
    }
}
