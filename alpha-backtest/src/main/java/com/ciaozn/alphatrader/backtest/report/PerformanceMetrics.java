package com.ciaozn.alphatrader.backtest.report;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Every number the performance report shows (FR-BT-04), computed once and then only read.
 *
 * <p>Money-shaped results (equity, P&L, fees) are exact {@link BigDecimal}s at
 * {@link com.ciaozn.alphatrader.common.model.Money} scale, so a re-run is bit-identical and a CI
 * threshold can be asserted with {@code isEqualByComparingTo} (SC-02). Statistics that are
 * ratios of a whole run - annualized return, Sharpe, win rate - are {@code double}, because they
 * are defined by square roots and fractional exponents that decimal arithmetic cannot express;
 * they are wrapped in {@link OptionalDouble} rather than left as {@code NaN} so that an undefined
 * value can never silently pass a threshold assertion.
 *
 * <p>A statistic is undefined, not zero, when its denominator is empty: Sharpe needs at least two
 * periods and a non-zero dispersion, win rate needs at least one closed trade, profit factor needs
 * at least one loser. Reporting zero there would read as "no risk" and "no losses", which is the
 * opposite of the truth for a run that never traded.
 */
public record PerformanceMetrics(
        BigDecimal startingEquity,
        BigDecimal finalEquity,
        BigDecimal netPnl,
        BigDecimal totalReturn,
        int periods,
        double yearsElapsed,
        OptionalDouble annualizedReturn,
        OptionalDouble sharpe,
        BigDecimal maxDrawdown,
        Optional<Drawdown> worstDrawdown,
        int closedTrades,
        int openTrades,
        int wins,
        int losses,
        OptionalDouble winRate,
        BigDecimal grossProfit,
        BigDecimal grossLoss,
        Optional<BigDecimal> profitFactor,
        BigDecimal averageWin,
        BigDecimal averageLoss,
        Optional<BigDecimal> payoffRatio,
        BigDecimal expectancy,
        BigDecimal bestTrade,
        BigDecimal worstTrade,
        BigDecimal totalFees) {

    /**
     * The worst peak-to-trough episode: the equity fell {@code depth} of its value between
     * {@code peakTs} and {@code troughTs}. Absent when the curve never closed below a previous
     * high, which is exactly the case where a drawdown of zero has no episode to point at.
     */
    public record Drawdown(long peakTs, long troughTs, BigDecimal depth) {
    }
}
