package com.ciaozn.alphatrader.backtest.report;

import com.ciaozn.alphatrader.common.model.Money;
import com.ciaozn.alphatrader.common.model.Symbol;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The expected numbers here come from an independent reference implementation
 * (/tmp/metrics_decimal.py: Python {@code decimal} at MathContext(24, HALF_UP) then quantized to
 * scale 8 for the exact values, plain IEEE doubles for the statistics), not from reading back
 * what the code produced. Fixture A/B/E are hand-built equity series small enough to check by
 * hand; the trade statistics fixture is six closed trades covering a win, a loss and a break-even
 * plus one still open.
 */
class PerformanceAnalyzerTest {

    private static final Symbol BTC = Symbol.parse("BTCUSDT.PERP");
    private static final long T0 = 1_700_006_400_000L;
    private static final Duration HOUR = Duration.ofHours(1);
    private static final Duration QUARTER = Duration.ofDays(90);

    /** 1e-12 is far below any ulp of the values asserted, and far above any formula error. */
    private static final Offset<Double> EXACT = Offset.offset(1e-12);
    /** Math.pow and Python's {@code **} may differ in the last ulp; nothing else may. */
    private static final Offset<Double> POW = Offset.offset(1e-9);

    private static EquityCurve curve(Duration period, String startingEquity, String... equities) {
        List<EquityCurve.Point> points = new ArrayList<>();
        for (int i = 0; i < equities.length; i++) {
            points.add(new EquityCurve.Point(T0 + i * period.toMillis(), Money.of(equities[i])));
        }
        return new EquityCurve(Money.of(startingEquity), points);
    }

    private static Trade trade(String netPnl, String fees, boolean open) {
        BigDecimal fee = Money.of(fees);
        BigDecimal net = Money.of(netPnl);
        return new Trade(BTC, Trade.Direction.LONG, T0, T0 + HOUR.toMillis(),
                Money.of("1"), Money.of("1"), Money.of("100"), Money.of("100"),
                net.add(fee), fee, net, open);
    }

    // ------------------------------------------------------------------ equity statistics

    @Test
    void totalReturnAndMaxDrawdownComeOutOfTheCurveExactly() {
        EquityCurve curve = curve(HOUR, "10000", "10100", "10050", "10200", "10200", "9900", "10400");

        PerformanceMetrics metrics = PerformanceAnalyzer.analyze(curve, List.of(), HOUR);

        assertThat(metrics.periods()).isEqualTo(6);
        assertThat(metrics.startingEquity()).isEqualByComparingTo("10000");
        assertThat(metrics.finalEquity()).isEqualByComparingTo("10400");
        assertThat(metrics.netPnl()).isEqualByComparingTo("400");
        assertThat(metrics.totalReturn()).isEqualTo(Money.of("0.04000000"));
        assertThat(metrics.maxDrawdown()).isEqualTo(Money.of("0.02941176"));
    }

    @Test
    void theWorstDrawdownNamesThePeakAndTheTroughThatBoundIt() {
        EquityCurve curve = curve(HOUR, "10000", "10100", "10050", "10200", "10200", "9900", "10400");

        PerformanceMetrics.Drawdown worst =
                PerformanceAnalyzer.analyze(curve, List.of(), HOUR).worstDrawdown().orElseThrow();

        // peak 10200 two hours in, trough 9900 four hours in: 300/10200
        assertThat(worst.peakTs()).isEqualTo(T0 + 2 * HOUR.toMillis());
        assertThat(worst.troughTs()).isEqualTo(T0 + 4 * HOUR.toMillis());
        assertThat(worst.depth()).isEqualTo(Money.of("0.02941176"));
    }

    @Test
    void sharpeAnnualizesTheSampledPeriodReturns() {
        EquityCurve curve = curve(HOUR, "10000", "10100", "10050", "10200", "10200", "9900", "10400");

        double sharpe = PerformanceAnalyzer.analyze(curve, List.of(), HOUR).sharpe().orElseThrow();

        // mean 0.006844693980665284 / sample std 0.026389343227645023 * sqrt(8760)
        assertThat(sharpe).isCloseTo(24.276021161556166, EXACT);
    }

    @Test
    void annualizedReturnCompoundsOverTheCalendarTimeTheRunActuallySpanned() {
        EquityCurve curve = curve(QUARTER, "10000",
                "10200", "9900", "10500", "11000", "10700", "11400", "11100", "12000", "12500");

        PerformanceMetrics metrics = PerformanceAnalyzer.analyze(curve, List.of(), QUARTER);

        assertThat(metrics.totalReturn()).isEqualTo(Money.of("0.25000000"));
        assertThat(metrics.yearsElapsed()).isCloseTo(2.219178082191781, EXACT);
        // 1.25 ^ (1 / 2.219...) - 1, not 25% times some factor: compounding is what makes a
        // two-year run comparable with a one-year one
        assertThat(metrics.annualizedReturn().orElseThrow()).isCloseTo(0.10578151792671897, POW);
        assertThat(metrics.sharpe().orElseThrow()).isCloseTo(1.1989048022634579, EXACT);
        assertThat(metrics.maxDrawdown()).isEqualTo(Money.of("0.02941176"));
    }

    @Test
    void aLosingRunKeepsItsDrawdownAndReportsANegativeSharpe() {
        EquityCurve curve = curve(HOUR, "10000", "9000", "8000", "7000", "7500");

        PerformanceMetrics metrics = PerformanceAnalyzer.analyze(curve, List.of(), HOUR);

        assertThat(metrics.totalReturn()).isEqualTo(Money.of("-0.25000000"));
        assertThat(metrics.maxDrawdown()).isEqualTo(Money.of("0.30000000"));
        PerformanceMetrics.Drawdown worst = metrics.worstDrawdown().orElseThrow();
        // the peak is the starting equity, which sits one period before the first sample
        assertThat(worst.peakTs()).isEqualTo(T0 - HOUR.toMillis());
        assertThat(worst.troughTs()).isEqualTo(T0 + 2 * HOUR.toMillis());
        assertThat(metrics.sharpe().orElseThrow()).isCloseTo(-67.09810149771654, EXACT);
    }

    @Test
    void aFlatCurveHasNoSharpeBecauseThereIsNoDispersionToDivideBy() {
        EquityCurve curve = curve(HOUR, "10000", "10000", "10000", "10000");

        PerformanceMetrics metrics = PerformanceAnalyzer.analyze(curve, List.of(), HOUR);

        assertThat(metrics.totalReturn()).isEqualByComparingTo("0");
        assertThat(metrics.maxDrawdown()).isEqualByComparingTo("0");
        assertThat(metrics.worstDrawdown()).as("never fell below a previous high").isEmpty();
        // zero divided by zero is undefined, not 0.0 and not infinity: printing 0.0 here would
        // read as "risk-free", which is the opposite of "we cannot tell"
        assertThat(metrics.sharpe()).isEmpty();
        assertThat(metrics.annualizedReturn().orElseThrow()).isCloseTo(0.0, EXACT);
    }

    @Test
    void aSinglePeriodHasNoSharpeButStillHasAReturn() {
        EquityCurve curve = curve(HOUR, "10000", "10500");

        PerformanceMetrics metrics = PerformanceAnalyzer.analyze(curve, List.of(), HOUR);

        assertThat(metrics.periods()).isEqualTo(1);
        assertThat(metrics.totalReturn()).isEqualTo(Money.of("0.05000000"));
        assertThat(metrics.sharpe()).as("one period has no dispersion").isEmpty();
        assertThat(metrics.maxDrawdown()).isEqualByComparingTo("0");
    }

    @Test
    void annualizingAShortRunExtrapolatesViolentlyWhichIsWhyThresholdsUseTotalReturn() {
        EquityCurve curve = curve(HOUR, "10000", "10500");

        double annualized = PerformanceAnalyzer.analyze(curve, List.of(), HOUR)
                .annualizedReturn().orElseThrow();

        // 5% over one hour compounded to a year. Reported as-is and never asserted against a
        // threshold in CI: total return and max drawdown are the window-honest numbers.
        assertThat(annualized).isGreaterThan(1e100);
    }

    @Test
    void anAccountThatHitsZeroIsAFullDrawdownNotADivisionByZero() {
        EquityCurve curve = curve(HOUR, "10000", "8000", "0");

        PerformanceMetrics metrics = PerformanceAnalyzer.analyze(curve, List.of(), HOUR);

        assertThat(metrics.totalReturn()).isEqualTo(Money.of("-1.00000000"));
        assertThat(metrics.maxDrawdown()).isEqualTo(Money.of("1.00000000"));
        assertThat(metrics.worstDrawdown().orElseThrow().troughTs()).isEqualTo(T0 + HOUR.toMillis());
        // 1 + totalReturn is 0, so there is no geometric rate; and the return series would
        // divide by the zero equity, so Sharpe is out too
        assertThat(metrics.annualizedReturn()).isEmpty();
        assertThat(metrics.sharpe()).isEmpty();
    }

    @Test
    void anAccountThatWentPastZeroStillReportsItsDrawdown() {
        EquityCurve curve = curve(HOUR, "10000", "8000", "-500");

        PerformanceMetrics metrics = PerformanceAnalyzer.analyze(curve, List.of(), HOUR);

        assertThat(metrics.totalReturn()).isEqualTo(Money.of("-1.05000000"));
        assertThat(metrics.maxDrawdown()).isEqualTo(Money.of("1.05000000"));
        assertThat(metrics.annualizedReturn()).as("a negative base has no real exponent").isEmpty();
        assertThat(metrics.sharpe()).isEmpty();
    }

    // ------------------------------------------------------------------ trade statistics

    @Test
    void winRateCountsABreakEvenTradeInTheDenominatorButInNeitherNumerator() {
        List<Trade> trades = closedTrades("100", "-80", "50", "0", "200", "-20");

        PerformanceMetrics metrics = PerformanceAnalyzer.analyze(
                curve(HOUR, "10000", "10000"), trades, HOUR);

        assertThat(metrics.closedTrades()).isEqualTo(6);
        assertThat(metrics.wins()).isEqualTo(3);
        assertThat(metrics.losses()).isEqualTo(2);
        assertThat(metrics.winRate().orElseThrow()).isCloseTo(0.5, EXACT);
        assertThat(metrics.grossProfit()).isEqualByComparingTo("350");
        assertThat(metrics.grossLoss()).isEqualByComparingTo("100");
        assertThat(metrics.profitFactor().orElseThrow()).isEqualByComparingTo("3.5");
        assertThat(metrics.averageWin()).isEqualTo(Money.of("116.66666667"));
        assertThat(metrics.averageLoss()).isEqualByComparingTo("50");
        assertThat(metrics.payoffRatio().orElseThrow()).isEqualTo(Money.of("2.33333333"));
        assertThat(metrics.expectancy()).isEqualTo(Money.of("41.66666667"));
        assertThat(metrics.bestTrade()).isEqualByComparingTo("200");
        assertThat(metrics.worstTrade()).isEqualByComparingTo("-80");
    }

    @Test
    void totalFeesIncludeTheTradeStillOpen() {
        List<Trade> trades = new ArrayList<>(closedTrades("100", "-80"));
        trades.add(trade("12.5", "2.00", true));

        PerformanceMetrics metrics = PerformanceAnalyzer.analyze(
                curve(HOUR, "10000", "10000"), trades, HOUR);

        // 1.50 + 1.50 on the closed trades, 2.00 on the open one: the fee was really paid
        assertThat(metrics.totalFees()).isEqualByComparingTo("5");
        assertThat(metrics.openTrades()).isEqualTo(1);
        assertThat(metrics.closedTrades()).isEqualTo(2);
    }

    @Test
    void anOpenTradeIsReportedButLeftOutOfWinRateAndProfitFactor() {
        List<Trade> trades = new ArrayList<>(closedTrades("100", "-80"));
        trades.add(trade("500", "1.00", true));

        PerformanceMetrics metrics = PerformanceAnalyzer.analyze(
                curve(HOUR, "10000", "10000"), trades, HOUR);

        // counting an unrealized 500 as a win would make the numbers depend on where the data
        // happened to stop rather than on the strategy
        assertThat(metrics.closedTrades()).isEqualTo(2);
        assertThat(metrics.openTrades()).isEqualTo(1);
        assertThat(metrics.winRate().orElseThrow()).isCloseTo(0.5, EXACT);
        assertThat(metrics.profitFactor().orElseThrow()).isEqualByComparingTo("1.25");
        assertThat(metrics.bestTrade()).as("the open trade is not the best trade").isEqualByComparingTo("100");
    }

    @Test
    void aRunThatNeverTradedHasNoWinRateAndNoProfitFactorRatherThanZeros() {
        PerformanceMetrics metrics = PerformanceAnalyzer.analyze(
                curve(HOUR, "10000", "10000", "10000"), List.of(), HOUR);

        assertThat(metrics.closedTrades()).isZero();
        assertThat(metrics.winRate()).as("0.0 would read as 'every trade lost'").isEmpty();
        assertThat(metrics.profitFactor()).isEmpty();
        assertThat(metrics.payoffRatio()).isEmpty();
        assertThat(metrics.expectancy()).isEqualByComparingTo("0");
        assertThat(metrics.bestTrade()).isEqualByComparingTo("0");
        assertThat(metrics.worstTrade()).isEqualByComparingTo("0");
        assertThat(metrics.totalFees()).isEqualByComparingTo("0");
        assertThat(metrics.grossProfit()).isEqualByComparingTo("0");
        assertThat(metrics.grossLoss()).isEqualByComparingTo("0");
    }

    @Test
    void theBestTradeIsTheLeastBadOneWhenEveryTradeLost() {
        PerformanceMetrics metrics = PerformanceAnalyzer.analyze(
                curve(HOUR, "10000", "10000"), closedTrades("-5", "-80", "-30"), HOUR);

        assertThat(metrics.wins()).isZero();
        assertThat(metrics.bestTrade()).as("0.00 would read as a break-even trade").isEqualByComparingTo("-5");
        assertThat(metrics.worstTrade()).isEqualByComparingTo("-80");
        assertThat(metrics.averageWin()).isEqualByComparingTo("0");
        assertThat(metrics.averageLoss()).isEqualByComparingTo("38.33333333");
        assertThat(metrics.profitFactor().orElseThrow())
                .as("defined, and zero: nothing earned per dollar lost")
                .isEqualByComparingTo("0");
        assertThat(metrics.payoffRatio())
                .as("averageWin's zero is a fallback, not a measurement to divide by")
                .isEmpty();
    }

    @Test
    void profitFactorIsUndefinedWhenNothingEverLost() {
        PerformanceMetrics metrics = PerformanceAnalyzer.analyze(
                curve(HOUR, "10000", "10000"), closedTrades("5", "80"), HOUR);

        assertThat(metrics.profitFactor()).as("unbounded, not 0 and not 1").isEmpty();
        assertThat(metrics.payoffRatio()).as("no losing trade to compare against").isEmpty();
        assertThat(metrics.winRate().orElseThrow()).isCloseTo(1.0, EXACT);
    }

    // ------------------------------------------------------------------ refusals

    @Test
    void refusesAnEmptyCurveInsteadOfReportingAFlatZeroRiskRun() {
        EquityCurve empty = new EquityCurve(Money.of("10000"), List.of());

        assertThatThrownBy(() -> PerformanceAnalyzer.analyze(empty, List.of(), HOUR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty equity curve");
    }

    @Test
    void refusesANonPositiveSamplingPeriod() {
        EquityCurve curve = curve(HOUR, "10000", "10100");

        assertThatThrownBy(() -> PerformanceAnalyzer.analyze(curve, List.of(), Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be positive");
    }

    @Test
    void refusesACurveThatIsNotStrictlyAscendingInBusinessTime() {
        List<EquityCurve.Point> repeating = List.of(
                new EquityCurve.Point(T0, Money.of("10000")),
                new EquityCurve.Point(T0, Money.of("10100")));

        assertThatThrownBy(() -> new EquityCurve(Money.of("10000"), repeating))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ascend by business time");
    }

    @Test
    void refusesANonPositiveStartingEquityBecauseNothingCanBeAReturnBaseline() {
        assertThatThrownBy(() -> new EquityCurve(Money.of("0"),
                List.of(new EquityCurve.Point(T0, Money.of("1")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be > 0");
    }

    private static List<Trade> closedTrades(String... netPnls) {
        List<Trade> trades = new ArrayList<>();
        for (String netPnl : netPnls) {
            trades.add(trade(netPnl, "1.50", false));
        }
        return trades;
    }
}
