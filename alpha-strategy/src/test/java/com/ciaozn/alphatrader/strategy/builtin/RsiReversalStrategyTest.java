package com.ciaozn.alphatrader.strategy.builtin;

import com.ciaozn.alphatrader.common.event.SignalEvent;
import com.ciaozn.alphatrader.common.model.Direction;
import com.ciaozn.alphatrader.common.model.Interval;
import com.ciaozn.alphatrader.common.model.Side;
import com.ciaozn.alphatrader.common.model.Symbol;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RsiReversalStrategyTest {

    private static final Symbol BTC = Symbol.parse("BTCUSDT.PERP");
    private static final long T0 = 1_700_000_000_000L;

    /** RSI(3): three falling bars saturate it at 0, three rising bars push it past 70. */
    private static final double[] V_SHAPE = {100, 99, 98, 97, 96, 98, 100, 102};

    private RsiReversalStrategy strategy(RsiReversalStrategy.Params params) {
        return new RsiReversalStrategy("rsi-3", Interval.H1, Set.of(BTC), params);
    }

    private RsiReversalStrategy.Params params() {
        return new RsiReversalStrategy.Params(3, 30, 70, 3, 0);
    }

    @Test
    void buysOversoldAndClosesLongOnOverbought() {
        StrategyHarness harness = new StrategyHarness();
        RsiReversalStrategy strategy = strategy(params());

        harness.feedCloses(strategy, BTC, T0, V_SHAPE);

        assertThat(harness.signals).hasSize(2);
        SignalEvent entry = harness.signals.get(0);
        assertThat(entry.direction()).isEqualTo(Direction.LONG);
        assertThat(entry.strategyId()).isEqualTo("rsi-3");
        assertThat(entry.reason()).contains("RSI3=0.00").contains("below oversold 30.0");
        // RSI(3) becomes ready on the 4th price (index 3)
        assertThat(entry.timestamp()).isEqualTo(T0 + 3 * 3_600_000L + 3_599_999L);

        SignalEvent exit = harness.signals.get(1);
        assertThat(exit.direction()).isEqualTo(Direction.FLAT);
        assertThat(exit.reason()).contains("above overbought 70.0").contains("closing long");
        assertThat(exit.timestamp()).isEqualTo(T0 + 6 * 3_600_000L + 3_599_999L);
    }

    @Test
    void doesNotRepeatEntryWhileOversoldPersists() {
        StrategyHarness harness = new StrategyHarness();
        // stays below the oversold threshold for four consecutive bars
        harness.feedCloses(strategy(params()), BTC, T0, 100, 99, 98, 97, 96, 95, 94);

        assertThat(harness.signals).hasSize(1);
        assertThat(harness.signals.get(0).direction()).isEqualTo(Direction.LONG);
    }

    @Test
    void doesNotRepeatExitAfterFlattening() {
        StrategyHarness harness = new StrategyHarness();
        RsiReversalStrategy strategy = strategy(params());

        // entry at index 3, exit at index 6 while the portfolio still reads flat
        harness.feedCloses(strategy, BTC, T0, 100, 99, 98, 97, 96, 98, 100);
        // the entry fill lands late and re-creates the long the FLAT was emitted for
        harness.portfolio.applyFill(BTC, Side.BUY, new BigDecimal("98"), new BigDecimal("1"), BigDecimal.ZERO);
        // the rally stays overbought for three more bars
        harness.feedCloses(strategy, BTC, T0 + 7 * 3_600_000L, 102, 104, 106);

        long flatSignals = harness.signals.stream()
                .filter(signal -> signal.direction() == Direction.FLAT)
                .count();
        // one exit for the desired long, one more once the fill actually created it - never one per bar
        assertThat(flatSignals).isEqualTo(2);
    }

    @Test
    void exitsAnExternallyHeldLongWhenOverbought() {
        StrategyHarness harness = new StrategyHarness();
        // position opened by something else (P3 reconciliation), strategy never desired it
        harness.portfolio.applyFill(BTC, Side.BUY, new BigDecimal("99"), new BigDecimal("1"), BigDecimal.ZERO);
        RsiReversalStrategy strategy = strategy(params());

        harness.feedCloses(strategy, BTC, T0, 100, 101, 102, 103);

        assertThat(harness.signals).hasSize(1);
        assertThat(harness.signals.get(0).direction()).isEqualTo(Direction.FLAT);
    }

    @Test
    void volatilityFilterBlocksEntryInADeadMarket() {
        double[] closes = {100, 99, 98, 97};
        StrategyHarness blocked = new StrategyHarness();
        StrategyHarness allowed = new StrategyHarness();
        RsiReversalStrategy blockedStrategy = strategy(new RsiReversalStrategy.Params(3, 30, 70, 3, 2.0));
        RsiReversalStrategy allowedStrategy = strategy(new RsiReversalStrategy.Params(3, 30, 70, 3, 2.0));

        // flat bars: true range comes only from the 1-point close-to-close move -> ATR ~1% of price
        for (int i = 0; i < closes.length; i++) {
            blocked.feed(blockedStrategy, BTC, T0 + i * 3_600_000L, closes[i], closes[i], closes[i]);
        }
        // wide bars: ATR ~6% of price, above the 2% floor
        for (int i = 0; i < closes.length; i++) {
            allowed.feed(allowedStrategy, BTC, T0 + i * 3_600_000L,
                    closes[i] + 3, closes[i] - 3, closes[i]);
        }

        assertThat(blocked.signals).isEmpty();
        assertThat(allowed.signals).hasSize(1);
        assertThat(allowed.signals.get(0).direction()).isEqualTo(Direction.LONG);
        assertThat(allowed.signals.get(0).reason()).contains("ATR3=");
    }

    @Test
    void exposesIdentityAndValidatesParams() {
        RsiReversalStrategy strategy = strategy(params());
        assertThat(strategy.id()).isEqualTo("rsi-3");
        assertThat(strategy.interval()).isEqualTo(Interval.H1);
        assertThat(strategy.symbols()).containsExactly(BTC);
        assertThat(RsiReversalStrategy.TYPE).isEqualTo("rsi-reversal");
        assertThat(RsiReversalStrategy.Params.DEFAULT.period()).isEqualTo(14);
        assertThat(RsiReversalStrategy.Params.DEFAULT.volatilityFilterEnabled()).isFalse();
        assertThat(params().volatilityFilterEnabled()).isFalse();
        assertThat(new RsiReversalStrategy.Params(14, 30, 70, 14, 0.5).volatilityFilterEnabled()).isTrue();

        assertThatThrownBy(() -> new RsiReversalStrategy.Params(14, 70, 30, 14, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RsiReversalStrategy.Params(14, 0, 70, 14, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RsiReversalStrategy.Params(14, 30, 100, 14, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RsiReversalStrategy.Params(1, 30, 70, 14, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RsiReversalStrategy.Params(14, 30, 70, 14, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
