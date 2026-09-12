package com.ciaozn.alphatrader.backtest.report;

import com.ciaozn.alphatrader.common.event.SignalEvent;
import com.ciaozn.alphatrader.common.model.Direction;
import com.ciaozn.alphatrader.common.model.Symbol;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SignalConsistencyReportTest {

    private static final Symbol BTC = Symbol.parse("BTCUSDT.PERP");
    private static final long BAR_1 = 1_700_000_000_000L;
    private static final long BAR_2 = BAR_1 + 3_600_000L;

    private static SignalEvent signal(String strategy, Direction direction, long barTime) {
        return SignalEvent.of(strategy, BTC, direction, 1.0, "cross", barTime);
    }

    @Test
    void identicalSequencesAgreeRegardlessOfOrder() {
        var report = SignalConsistencyReport.compare(
                List.of(signal("ma-cross-1", Direction.LONG, BAR_1), signal("rsi-reversal-1", Direction.FLAT, BAR_2)),
                List.of(signal("rsi-reversal-1", Direction.FLAT, BAR_2), signal("ma-cross-1", Direction.LONG, BAR_1)));

        assertThat(report.consistent()).isTrue();
        assertThat(report.matched()).isEqualTo(2);
        assertThat(report.render()).contains("Signals agree");
    }

    @Test
    void aSignalOnlyTheBacktestProducedIsReportedMissing() {
        // This is the shape of the drift SC-05 exists to catch: the same strategy, one mode silent.
        var report = SignalConsistencyReport.compare(
                List.of(signal("ma-cross-1", Direction.LONG, BAR_1)),
                List.of());

        assertThat(report.consistent()).isFalse();
        assertThat(report.missing()).hasSize(1);
        assertThat(report.extra()).isEmpty();
        assertThat(report.render()).contains("missing").contains("ma-cross-1").contains("BTCUSDT.PERP");
    }

    @Test
    void aSignalOnlyLiveProducedIsReportedExtra() {
        var report = SignalConsistencyReport.compare(
                List.of(),
                List.of(signal("ma-cross-1", Direction.SHORT, BAR_2)));

        assertThat(report.consistent()).isFalse();
        assertThat(report.extra()).hasSize(1);
        assertThat(report.render()).contains("extra").contains("SHORT");
    }

    @Test
    void directionIsPartOfIdentity() {
        // Long where the other side went short is not a match, it is two different decisions.
        var report = SignalConsistencyReport.compare(
                List.of(signal("ma-cross-1", Direction.LONG, BAR_1)),
                List.of(signal("ma-cross-1", Direction.SHORT, BAR_1)));

        assertThat(report.missing()).hasSize(1);
        assertThat(report.extra()).hasSize(1);
    }

    @Test
    void theBarTimeIsPartOfIdentity() {
        // Same strategy, same direction, one bar apart: a different moment, so a different decision.
        var report = SignalConsistencyReport.compare(
                List.of(signal("ma-cross-1", Direction.LONG, BAR_1)),
                List.of(signal("ma-cross-1", Direction.LONG, BAR_2)));

        assertThat(report.consistent()).isFalse();
    }

    @Test
    void duplicatesAreCountedNotCollapsed() {
        // Two signals from one strategy on one bar is a different run from one. A set comparison would
        // call these identical, which is the over-permissiveness that makes a check worthless.
        var report = SignalConsistencyReport.compare(
                List.of(signal("ma-cross-1", Direction.LONG, BAR_1), signal("ma-cross-1", Direction.LONG, BAR_1)),
                List.of(signal("ma-cross-1", Direction.LONG, BAR_1)));

        assertThat(report.consistent()).isFalse();
        assertThat(report.missing()).hasSize(1);
        assertThat(report.extra()).isEmpty();
    }

    @Test
    void twoEmptySequencesAgree() {
        // "Both were silent" is agreement, not an absent result - and it is worth being able to say so.
        assertThat(SignalConsistencyReport.compare(List.of(), List.of()).consistent()).isTrue();
    }
}
