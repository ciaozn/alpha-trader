package com.ciaozn.alphatrader.strategy;

import com.ciaozn.alphatrader.common.event.Event;
import com.ciaozn.alphatrader.common.event.FillEvent;
import com.ciaozn.alphatrader.common.event.KlineEvent;
import com.ciaozn.alphatrader.common.event.SignalEvent;
import com.ciaozn.alphatrader.common.event.TimerEvent;
import com.ciaozn.alphatrader.common.model.Direction;
import com.ciaozn.alphatrader.common.model.Interval;
import com.ciaozn.alphatrader.common.model.Kline;
import com.ciaozn.alphatrader.common.model.Side;
import com.ciaozn.alphatrader.common.model.Symbol;
import com.ciaozn.alphatrader.common.portfolio.Portfolio;
import com.ciaozn.alphatrader.common.time.VirtualClock;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class StrategyEngineTest {

    private static final Symbol BTC = Symbol.parse("BTCUSDT.PERP");
    private static final Symbol ETH = Symbol.parse("ETHUSDT.PERP");
    private static final long T0 = 1_700_000_000_000L;
    private static final long HOUR = 3_600_000L;

    private final Portfolio portfolio = new Portfolio(new BigDecimal("10000"));
    private final VirtualClock clock = new VirtualClock(T0);
    private final List<Event> published = new ArrayList<>();
    private final List<String> dispatchOrder = new ArrayList<>();

    /** Records everything the engine hands it, so delivery rules can be asserted directly. */
    private final class RecordingStrategy implements Strategy {

        private final String id;
        private final Interval interval;
        private final Set<Symbol> symbols;
        final List<KlineEvent> klines = new ArrayList<>();
        final List<FillEvent> fills = new ArrayList<>();
        final List<TimerEvent> timers = new ArrayList<>();
        boolean failOnKline;

        RecordingStrategy(String id, Interval interval, Symbol... symbols) {
            this.id = id;
            this.interval = interval;
            this.symbols = new LinkedHashSet<>(List.of(symbols));
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public Set<Symbol> symbols() {
            return symbols;
        }

        @Override
        public Interval interval() {
            return interval;
        }

        @Override
        public void onKline(KlineEvent event, StrategyContext context) {
            dispatchOrder.add(id);
            klines.add(event);
            if (failOnKline) {
                throw new IllegalStateException(id + " is broken on purpose");
            }
            if (context.bars(event.symbol(), event.interval()).size() == 1) {
                context.emit(event.symbol(), Direction.LONG, 1.0, "first bar");
            }
        }

        @Override
        public void onFill(FillEvent event, StrategyContext context) {
            dispatchOrder.add(id);
            fills.add(event);
        }

        @Override
        public void onTimer(TimerEvent event, StrategyContext context) {
            dispatchOrder.add(id);
            timers.add(event);
        }
    }

    private StrategyEngine engine(Strategy... strategies) {
        return new StrategyEngine(List.of(strategies), portfolio, clock);
    }

    private static KlineEvent closedBar(Symbol symbol, Interval interval, long openTime, double close) {
        return bar(symbol, interval, openTime, close, true);
    }

    private static KlineEvent bar(Symbol symbol, Interval interval, long openTime, double close, boolean closed) {
        long closeTime = openTime + interval.duration().toMillis() - 1;
        BigDecimal price = BigDecimal.valueOf(close);
        Kline kline = new Kline(openTime, price, price.add(BigDecimal.ONE), price.subtract(BigDecimal.ONE),
                price, BigDecimal.ONE, closeTime);
        return KlineEvent.of(symbol, interval, kline, closed, closeTime);
    }

    @Test
    void deliversClosedBarsToSubscribedStrategiesOnly() {
        RecordingStrategy btc = new RecordingStrategy("btc-1h", Interval.H1, BTC);
        RecordingStrategy eth = new RecordingStrategy("eth-1h", Interval.H1, ETH);
        RecordingStrategy otherInterval = new RecordingStrategy("btc-4h", Interval.H4, BTC);
        StrategyEngine engine = engine(btc, eth, otherInterval);

        engine.onEvent(closedBar(BTC, Interval.H1, T0, 100), published::add);

        assertThat(btc.klines).hasSize(1);
        assertThat(eth.klines).isEmpty();
        assertThat(otherInterval.klines).isEmpty();
        assertThat(engine.deliveredBars()).isEqualTo(1);
    }

    @Test
    void neverDeliversTheFormingBar() {
        RecordingStrategy strategy = new RecordingStrategy("btc-1h", Interval.H1, BTC);
        StrategyEngine engine = engine(strategy);

        engine.onEvent(bar(BTC, Interval.H1, T0, 100, false), published::add);

        assertThat(strategy.klines).isEmpty();
        assertThat(engine.deliveredBars()).isZero();
        assertThat(engine.droppedBars()).isZero();
    }

    @Test
    void marksThePortfolioBeforeDispatchingTheBarThatProducedTheSignal() {
        RecordingStrategy strategy = new RecordingStrategy("btc-1h", Interval.H1, BTC);
        StrategyEngine engine = engine(strategy);

        engine.onEvent(closedBar(BTC, Interval.H1, T0, 100), published::add);
        engine.onEvent(closedBar(BTC, Interval.H1, T0 + HOUR, 105), published::add);
        // duplicate and forming bars are dropped, so they must not move the mark either
        engine.onEvent(closedBar(BTC, Interval.H1, T0 + HOUR, 999), published::add);
        engine.onEvent(bar(BTC, Interval.H1, T0 + 2 * HOUR, 999, false), published::add);

        assertThat(portfolio.markOf(BTC)).isEqualByComparingTo("105");
        assertThat(strategy.klines).hasSize(2);
    }

    @Test
    void duplicateAndStaleBarsAreDroppedWithoutReInvokingStrategies() {
        RecordingStrategy strategy = new RecordingStrategy("btc-1h", Interval.H1, BTC);
        StrategyEngine engine = engine(strategy);

        engine.onEvent(closedBar(BTC, Interval.H1, T0, 100), published::add);
        engine.onEvent(closedBar(BTC, Interval.H1, T0, 101), published::add);
        engine.onEvent(closedBar(BTC, Interval.H1, T0 - HOUR, 99), published::add);
        engine.onEvent(closedBar(BTC, Interval.H1, T0 + HOUR, 102), published::add);

        assertThat(strategy.klines).hasSize(2);
        assertThat(strategy.klines.get(1).kline().openTime()).isEqualTo(T0 + HOUR);
        assertThat(engine.deliveredBars()).isEqualTo(2);
        assertThat(engine.droppedBars()).isEqualTo(2);
        // one signal per accepted bar, none for the re-delivered ones (spec edge case 1)
        assertThat(published).hasSize(1);
    }

    @Test
    void strategiesOnTheSameSeriesShareOneWindow() {
        RecordingStrategy first = new RecordingStrategy("a", Interval.H1, BTC);
        RecordingStrategy second = new RecordingStrategy("b", Interval.H1, BTC);
        StrategyEngine engine = engine(first, second);

        engine.onEvent(closedBar(BTC, Interval.H1, T0, 100), published::add);
        engine.onEvent(closedBar(BTC, Interval.H1, T0 + HOUR, 101), published::add);

        assertThat(engine.deliveredBars()).isEqualTo(2);
        assertThat(dispatchOrder).containsExactly("a", "b", "a", "b");
        // both saw a series holding exactly one bar on the first round: the bar is appended once
        assertThat(published).hasSize(2);
    }

    @Test
    void aThrowingStrategyDoesNotBlindTheOthers() {
        RecordingStrategy broken = new RecordingStrategy("broken", Interval.H1, BTC);
        RecordingStrategy healthy = new RecordingStrategy("healthy", Interval.H1, BTC);
        broken.failOnKline = true;
        StrategyEngine engine = engine(broken, healthy);

        engine.onEvent(closedBar(BTC, Interval.H1, T0, 100), published::add);

        assertThat(broken.klines).hasSize(1);
        assertThat(healthy.klines).hasSize(1);
        assertThat(published).hasSize(1);
    }

    @Test
    void routesFillsBySymbolAndTimersToEveryone() {
        RecordingStrategy btc = new RecordingStrategy("btc", Interval.H1, BTC);
        RecordingStrategy eth = new RecordingStrategy("eth", Interval.H1, ETH);
        StrategyEngine engine = engine(btc, eth);

        engine.onEvent(FillEvent.of("btc-1", BTC, Side.BUY, new BigDecimal("100"),
                new BigDecimal("0.001"), BigDecimal.ZERO, T0), published::add);
        engine.onEvent(TimerEvent.of("reconcile", T0), published::add);

        assertThat(btc.fills).hasSize(1);
        assertThat(eth.fills).isEmpty();
        assertThat(btc.timers).hasSize(1);
        assertThat(eth.timers).hasSize(1);
    }

    @Test
    void ignoresEventsThatAreDownstreamOfStrategies() {
        RecordingStrategy strategy = new RecordingStrategy("btc", Interval.H1, BTC);
        StrategyEngine engine = engine(strategy);

        engine.onEvent(SignalEvent.of("someone", BTC, Direction.LONG, 1.0, "x", T0), published::add);

        assertThat(dispatchOrder).isEmpty();
        assertThat(published).isEmpty();
    }

    @Test
    void exposesStrategiesInDispatchOrder() {
        RecordingStrategy first = new RecordingStrategy("a", Interval.H1, BTC);
        RecordingStrategy second = new RecordingStrategy("b", Interval.H1, ETH);
        StrategyEngine engine = engine(first, second);

        assertThat(engine.strategies()).containsExactly(first, second);
        assertThat(engine.portfolio()).isSameAs(portfolio);
    }
}
