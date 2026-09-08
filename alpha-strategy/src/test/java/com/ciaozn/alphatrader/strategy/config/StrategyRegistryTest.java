package com.ciaozn.alphatrader.strategy.config;

import com.ciaozn.alphatrader.common.event.Event;
import com.ciaozn.alphatrader.common.event.KlineEvent;
import com.ciaozn.alphatrader.common.event.SignalEvent;
import com.ciaozn.alphatrader.common.model.Direction;
import com.ciaozn.alphatrader.common.model.Interval;
import com.ciaozn.alphatrader.common.model.Kline;
import com.ciaozn.alphatrader.common.model.Symbol;
import com.ciaozn.alphatrader.common.portfolio.Portfolio;
import com.ciaozn.alphatrader.common.time.VirtualClock;
import com.ciaozn.alphatrader.strategy.ParamSet;
import com.ciaozn.alphatrader.strategy.Strategy;
import com.ciaozn.alphatrader.strategy.StrategyEngine;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StrategyRegistryTest {

    private static final Symbol BTC = Symbol.parse("BTCUSDT.PERP");
    private static final long T0 = 1_700_000_000_000L;
    private static final long HOUR = 3_600_000L;
    private static final double[] CROSSING_CLOSES = {15, 14, 13, 12, 11, 10, 11, 13, 15, 14, 12, 10};

    /** ServiceLoader discovery: the two built-ins from main/, the dummy from test resources. */
    private final StrategyRegistry registry = new StrategyRegistry();

    private static StrategySpec spec(String id, String type, boolean enabled,
                                     Map<String, String> params, String... symbols) {
        return StrategySpec.of(id, type, enabled, new LinkedHashSet<>(List.of(symbols)), "1h", params);
    }

    private static KlineEvent closedBar(Symbol symbol, long openTime, double close) {
        BigDecimal price = BigDecimal.valueOf(close);
        long closeTime = openTime + Interval.H1.duration().toMillis() - 1;
        return KlineEvent.of(symbol, Interval.H1,
                new Kline(openTime, price, price, price, price, BigDecimal.ONE, closeTime), true, closeTime);
    }

    private static StrategyEngine engine(List<Strategy> strategies) {
        return new StrategyEngine(strategies, new Portfolio(new BigDecimal("10000")), new VirtualClock(T0));
    }

    @Test
    void discoversFactoriesFromTheClasspath() {
        assertThat(registry.knownTypes()).contains("ma-cross", "rsi-reversal", DummyStrategyFactory.TYPE);
    }

    /** Spec scenario 6: a strategy nobody in main/ knows about, live through configuration alone. */
    @Test
    void enablesANewStrategyThroughConfigurationAlone() {
        List<Strategy> strategies = registry.build(List.of(
                spec("dummy-btc", DummyStrategyFactory.TYPE, true,
                        Map.of("threshold", "105", "window", "7", "loud", "true"), "BTCUSDT.PERP")));

        assertThat(strategies).hasSize(1);
        DummyStrategy dummy = (DummyStrategy) strategies.get(0);
        assertThat(dummy.id()).isEqualTo("dummy-btc");
        assertThat(dummy.symbols()).containsExactly(BTC);
        assertThat(dummy.interval()).isEqualTo(Interval.H1);
        assertThat(dummy.threshold()).isEqualTo(105.0);
        assertThat(dummy.window()).isEqualTo(7);
        assertThat(dummy.loud()).isTrue();

        // The engine was not touched: the configured strategy is dispatched like any built-in.
        List<Event> published = new ArrayList<>();
        StrategyEngine engine = engine(strategies);
        engine.onEvent(closedBar(BTC, T0, 100), published::add);
        engine.onEvent(closedBar(BTC, T0 + HOUR, 110), published::add);

        assertThat(dummy.seen()).hasSize(2);
        assertThat(published).hasSize(1);
        SignalEvent signal = (SignalEvent) published.get(0);
        assertThat(signal.strategyId()).isEqualTo("dummy-btc");
        assertThat(signal.direction()).isEqualTo(Direction.LONG);
    }

    @Test
    void buildsEnabledStrategiesInConfigurationOrder() {
        List<Strategy> strategies = registry.build(List.of(
                spec("rsi-btc", "rsi-reversal", false, Map.of(), "BTCUSDT.PERP"),
                spec("ma-eth", "ma-cross", true, Map.of(), "ETHUSDT.PERP"),
                spec("dummy-btc", DummyStrategyFactory.TYPE, true, Map.of(), "BTCUSDT.PERP")));

        assertThat(strategies).extracting(Strategy::id).containsExactly("ma-eth", "dummy-btc");
    }

    /** Kebab-case keys and a non-default parameter set must actually change strategy behaviour. */
    @Test
    void configuredParametersReachTheStrategy() {
        List<Strategy> strategies = registry.build(List.of(spec("ma-fast", "ma-cross", true,
                Map.of("fast-period", "2", "slow-period", "3", "allow-short", "true"), "BTCUSDT.PERP")));

        List<Event> published = new ArrayList<>();
        StrategyEngine engine = engine(strategies);
        for (int i = 0; i < CROSSING_CLOSES.length; i++) {
            engine.onEvent(closedBar(BTC, T0 + i * HOUR, CROSSING_CLOSES[i]), published::add);
        }

        // SMA2/SMA3 on this series cross up at index 7 and down at index 10 - SMA10/30 would stay silent
        assertThat(published).hasSize(2);
        assertThat(((SignalEvent) published.get(0)).direction()).isEqualTo(Direction.LONG);
        assertThat(((SignalEvent) published.get(0)).strategyId()).isEqualTo("ma-fast");
        assertThat(((SignalEvent) published.get(1)).direction()).isEqualTo(Direction.SHORT);
    }

    @Test
    void unknownTypeFailsFastAndListsKnownTypes() {
        assertThatThrownBy(() -> registry.build(List.of(
                spec("bollinger", "bollinger-breakout", true, Map.of(), "BTCUSDT.PERP"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown strategy type 'bollinger-breakout'")
                .hasMessageContaining("ma-cross");
    }

    @Test
    void duplicateIdFailsFast() {
        assertThatThrownBy(() -> registry.build(List.of(
                spec("same", "ma-cross", true, Map.of(), "BTCUSDT.PERP"),
                spec("same", DummyStrategyFactory.TYPE, true, Map.of(), "BTCUSDT.PERP"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate strategy id 'same'");
    }

    @Test
    void misspelledParameterKeyFailsFast() {
        assertThatThrownBy(() -> registry.build(List.of(
                spec("ma-typo", "ma-cross", true, Map.of("fastPeroid", "5"), "BTCUSDT.PERP"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown parameter")
                .hasMessageContaining("fastPeroid");
    }

    @Test
    void invalidParameterValueFailsFast() {
        assertThatThrownBy(() -> registry.build(List.of(
                spec("ma-bad", "ma-cross", true, Map.of("fastPeriod", "fast"), "BTCUSDT.PERP"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fastPeriod")
                .hasMessageContaining("not an integer");
    }

    @Test
    void rejectsAFactoryThatIgnoresTheConfiguredId() {
        StrategyRegistry renaming = new StrategyRegistry(List.of(new RenamingFactory()));

        assertThatThrownBy(() -> renaming.build(List.of(
                spec("configured-id", DummyStrategyFactory.TYPE, true, Map.of(), "BTCUSDT.PERP"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ignored the configured id");
    }

    @Test
    void rejectsTwoFactoriesClaimingTheSameType() {
        assertThatThrownBy(() -> new StrategyRegistry(List.of(new DummyStrategyFactory(), new RenamingFactory())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Steals the dummy's type and hardcodes an id, both on purpose. */
    private static final class RenamingFactory implements StrategyFactory {

        @Override
        public String type() {
            return DummyStrategyFactory.TYPE;
        }

        @Override
        public Strategy create(StrategySpec spec, ParamSet params) {
            return new DummyStrategy("hardcoded", spec.interval(), spec.symbols(), 1, 0, false);
        }
    }
}
