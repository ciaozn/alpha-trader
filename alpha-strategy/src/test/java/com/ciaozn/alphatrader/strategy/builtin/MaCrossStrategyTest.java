package com.ciaozn.alphatrader.strategy.builtin;

import com.ciaozn.alphatrader.common.event.SignalEvent;
import com.ciaozn.alphatrader.common.model.Direction;
import com.ciaozn.alphatrader.common.model.Interval;
import com.ciaozn.alphatrader.common.model.Symbol;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MaCrossStrategyTest {

    private static final Symbol BTC = Symbol.parse("BTCUSDT.PERP");
    private static final long T0 = 1_700_000_000_000L;

    /** Down-leg then up-leg then down-leg; with fast=2/slow=3 this crosses exactly twice. */
    private static final double[] CLOSES = {15, 14, 13, 12, 11, 10, 11, 13, 15, 14, 12, 10};

    private MaCrossStrategy strategy(boolean allowShort) {
        return new MaCrossStrategy("ma-2-3", Interval.H1, Set.of(BTC),
                new MaCrossStrategy.Params(2, 3, allowShort));
    }

    @Test
    void emitsLongOnGoldenCrossAndShortOnDeathCross() {
        StrategyHarness harness = new StrategyHarness();
        MaCrossStrategy strategy = strategy(true);

        harness.feedCloses(strategy, BTC, T0, CLOSES);

        assertThat(harness.signals).hasSize(2);
        SignalEvent golden = harness.signals.get(0);
        assertThat(golden.direction()).isEqualTo(Direction.LONG);
        assertThat(golden.strategyId()).isEqualTo("ma-2-3");
        assertThat(golden.symbol()).isEqualTo(BTC);
        assertThat(golden.strength()).isEqualTo(1.0);
        assertThat(golden.reason()).contains("golden cross").contains("SMA2=").contains("SMA3=");
        // the crossing bar is the 8th one (index 7); business time is its close time
        assertThat(golden.timestamp()).isEqualTo(T0 + 7 * 3_600_000L + 3_599_999L);

        SignalEvent death = harness.signals.get(1);
        assertThat(death.direction()).isEqualTo(Direction.SHORT);
        assertThat(death.reason()).contains("death cross");
        assertThat(death.timestamp()).isEqualTo(T0 + 10 * 3_600_000L + 3_599_999L);
    }

    @Test
    void flattensInsteadOfShortingWhenShortsDisallowed() {
        StrategyHarness harness = new StrategyHarness();
        harness.feedCloses(strategy(false), BTC, T0, CLOSES);

        assertThat(harness.signals).hasSize(2);
        assertThat(harness.signals.get(0).direction()).isEqualTo(Direction.LONG);
        assertThat(harness.signals.get(1).direction()).isEqualTo(Direction.FLAT);
    }

    @Test
    void staysSilentDuringWarmupAndInsideATrend() {
        StrategyHarness harness = new StrategyHarness();
        // steady uptrend: fast above slow from the first settled bar, but no crossing
        harness.feedCloses(strategy(true), BTC, T0, 10, 11, 12, 13, 14, 15, 16, 17);

        assertThat(harness.signals).isEmpty();
    }

    @Test
    void oneCrossProducesExactlyOneSignalEvenIfItPersists() {
        StrategyHarness harness = new StrategyHarness();
        // cross happens once, then the trend continues for five more bars
        harness.feedCloses(strategy(true), BTC, T0, 15, 14, 13, 12, 11, 10, 11, 13, 15, 17, 19, 21, 23);

        assertThat(harness.signals).hasSize(1);
        assertThat(harness.signals.get(0).direction()).isEqualTo(Direction.LONG);
    }

    @Test
    void keepsIndependentStatePerSymbol() {
        Symbol eth = Symbol.parse("ETHUSDT.PERP");
        MaCrossStrategy strategy = new MaCrossStrategy("ma-multi", Interval.H1, Set.of(BTC, eth),
                new MaCrossStrategy.Params(2, 3, true));
        StrategyHarness harness = new StrategyHarness();

        harness.feedCloses(strategy, BTC, T0, CLOSES);
        // ETH only gets the up-leg, so it never crosses down
        harness.feedCloses(strategy, eth, T0, 15, 14, 13, 12, 11, 10, 11, 13, 15, 17, 19, 21);

        assertThat(harness.signals).hasSize(3);
        assertThat(harness.signals.stream().filter(s -> s.symbol().equals(eth)).count()).isEqualTo(1);
        assertThat(harness.signals.stream().filter(s -> s.symbol().equals(eth)))
                .allSatisfy(signal -> assertThat(signal.direction()).isEqualTo(Direction.LONG));
    }

    @Test
    void exposesIdentityAndValidatesParams() {
        MaCrossStrategy strategy = strategy(true);
        assertThat(strategy.id()).isEqualTo("ma-2-3");
        assertThat(strategy.interval()).isEqualTo(Interval.H1);
        assertThat(strategy.symbols()).containsExactly(BTC);

        assertThatThrownBy(() -> new MaCrossStrategy.Params(30, 10, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MaCrossStrategy.Params(0, 10, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MaCrossStrategy("x", Interval.H1, Set.of(), MaCrossStrategy.Params.DEFAULT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(MaCrossStrategy.Params.DEFAULT.fastPeriod()).isEqualTo(10);
        assertThat(MaCrossStrategy.Params.DEFAULT.slowPeriod()).isEqualTo(30);
        assertThat(MaCrossStrategy.Params.DEFAULT.allowShort()).isTrue();
        assertThat(MaCrossStrategy.TYPE).isEqualTo("ma-cross");
    }
}
