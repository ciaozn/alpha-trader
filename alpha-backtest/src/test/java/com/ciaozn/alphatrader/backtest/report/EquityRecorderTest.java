package com.ciaozn.alphatrader.backtest.report;

import com.ciaozn.alphatrader.common.model.Money;
import com.ciaozn.alphatrader.common.model.Side;
import com.ciaozn.alphatrader.common.model.Symbol;
import com.ciaozn.alphatrader.common.portfolio.Portfolio;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EquityRecorderTest {

    private static final Symbol BTC = Symbol.parse("BTCUSDT.PERP");
    private static final long T0 = 1_700_006_400_000L;
    private static final long HOUR = 3_600_000L;

    private Portfolio portfolio;
    private EquityRecorder recorder;

    @BeforeEach
    void setUp() {
        portfolio = new Portfolio(new BigDecimal("10000"));
        recorder = new EquityRecorder(portfolio);
    }

    /** Opens one unit long at 100 and marks it at {@code mark}, charging {@code fee}. */
    private void openLong(String mark, String fee) {
        portfolio.mark(BTC, new BigDecimal(mark));
        portfolio.applyFill(BTC, Side.BUY, new BigDecimal("100"), BigDecimal.ONE, new BigDecimal(fee));
    }

    @Test
    void samplesTheBookOncePerClosedRound() {
        recorder.afterRound(T0);
        openLong("110", "1");
        recorder.afterRound(T0 + HOUR);

        EquityCurve curve = recorder.curve();
        assertThat(curve.startingEquity()).isEqualByComparingTo("10000");
        assertThat(curve.points()).containsExactly(
                new EquityCurve.Point(T0, Money.of("10000")),
                // cash 9999 after the fee, plus 10 unrealized at the 110 mark
                new EquityCurve.Point(T0 + HOUR, Money.of("10009")));
        assertThat(curve.totalReturn()).isEqualByComparingTo("0.0009");
        assertThat(recorder.rounds()).isEqualTo(2);
    }

    @Test
    void aSecondSampleAtTheSameInstantReplacesTheFirst() {
        recorder.sample(T0);
        openLong("110", "1");
        // two series closing together produce two rounds with one timestamp; equity as of that
        // instant means after both, so the later sample is the one that stands. Keeping both
        // would insert a zero-length period and deflate every volatility-based metric.
        recorder.sample(T0);

        EquityCurve curve = recorder.curve();
        assertThat(curve.points()).containsExactly(new EquityCurve.Point(T0, Money.of("10009")));
        assertThat(recorder.rounds()).as("both samples happened").isEqualTo(2);
    }

    @Test
    void anUntouchedBookYieldsAnEmptyCurveBasedOnTheStartingEquity() {
        EquityCurve curve = recorder.curve();

        assertThat(curve.isEmpty()).isTrue();
        assertThat(recorder.rounds()).isZero();
        assertThat(curve.startingEquity()).isEqualByComparingTo("10000");
        assertThat(curve.finalEquity()).as("nothing was ever sampled").isEqualByComparingTo("10000");
        assertThat(curve.totalReturn()).isEqualByComparingTo("0");
    }

    @Test
    void aCurveSampledOutOfOrderIsRefusedRatherThanPlottedBackwards() {
        recorder.sample(T0 + HOUR);
        recorder.sample(T0);

        // the feeder merges by business time, so this cannot happen through it; refusing turns a
        // future wiring mistake into a failure instead of a silently wrong equity curve
        assertThatThrownBy(() -> recorder.curve())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ascend by business time");
    }

    @Test
    void equityFallsWhenAMarkedPositionLoses() {
        openLong("110", "0");
        recorder.sample(T0);
        portfolio.mark(BTC, new BigDecimal("90"));
        recorder.sample(T0 + HOUR);

        assertThat(recorder.curve().points())
                .extracting(EquityCurve.Point::equity)
                .containsExactly(Money.of("10010"), Money.of("9990"));
        assertThat(recorder.curve().totalReturn()).as("measured against the starting equity")
                .isEqualByComparingTo("-0.001");
    }

    @Test
    void theCurveIsImmutable() {
        recorder.sample(T0);
        List<EquityCurve.Point> points = recorder.curve().points();

        assertThatThrownBy(() -> points.add(new EquityCurve.Point(T0 + HOUR, Money.of("1"))))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
