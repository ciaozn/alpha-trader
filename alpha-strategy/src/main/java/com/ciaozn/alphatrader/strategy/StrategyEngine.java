package com.ciaozn.alphatrader.strategy;

import com.ciaozn.alphatrader.common.event.Event;
import com.ciaozn.alphatrader.common.event.FillEvent;
import com.ciaozn.alphatrader.common.event.KlineEvent;
import com.ciaozn.alphatrader.common.event.TimerEvent;
import com.ciaozn.alphatrader.common.model.Interval;
import com.ciaozn.alphatrader.common.model.Symbol;
import com.ciaozn.alphatrader.common.portfolio.Portfolio;
import com.ciaozn.alphatrader.common.time.Clock;
import com.ciaozn.alphatrader.engine.EventHandler;
import com.ciaozn.alphatrader.engine.EventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Multiplexes the event loop onto the registered strategies (FR-ST-01). This is the only
 * class that knows both the engine and the SPI, and it never changes when a strategy is
 * added: new strategies arrive through configuration (spec scenario 6, NFR-06).
 *
 * <p>Delivery rules, all of which exist to keep backtest and live identical:
 * <ul>
 *   <li>only CLOSED bars are delivered - the forming bar would be look-ahead bias (FR-BT-02);</li>
 *   <li>a bar is delivered only to strategies subscribed to that symbol AND interval;</li>
 *   <li>one {@link BarSeries} per symbol/interval is shared by those strategies and the bar is
 *       appended once. A duplicate or out-of-order push is dropped there, and the strategies are
 *       NOT invoked for it: re-evaluating an identical window would emit a duplicate signal
 *       (spec edge case 1);</li>
 *   <li>strategies run in registration order, which is the configuration order, so the signal
 *       sequence is reproducible (NFR-04);</li>
 *   <li>a throwing strategy is isolated - it must not blind the others.</li>
 * </ul>
 *
 * <p>Signals are published through the current round's {@link EventPublisher}, so the whole
 * signal -&gt; risk -&gt; order chain of one bar closes inside a single engine round.
 */
public final class StrategyEngine implements EventHandler {

    /** Enough bars for any v1 indicator (slowest built-in window is SMA30) with room to grow. */
    public static final int DEFAULT_BAR_CAPACITY = 1000;

    private static final Logger log = LoggerFactory.getLogger(StrategyEngine.class);

    private final List<Strategy> strategies;
    private final Map<SeriesKey, List<Strategy>> klineSubscribers;
    private final Map<Symbol, List<Strategy>> fillSubscribers;
    private final Map<SeriesKey, BarSeries> series = new LinkedHashMap<>();
    private final Portfolio portfolio;
    private final Clock clock;
    private final int barCapacity;
    private long deliveredBars;
    private long droppedBars;

    public StrategyEngine(List<Strategy> strategies, Portfolio portfolio, Clock clock) {
        this(strategies, portfolio, clock, DEFAULT_BAR_CAPACITY);
    }

    public StrategyEngine(List<Strategy> strategies, Portfolio portfolio, Clock clock, int barCapacity) {
        this.strategies = List.copyOf(strategies);
        this.portfolio = portfolio;
        this.clock = clock;
        this.barCapacity = barCapacity;
        this.klineSubscribers = new LinkedHashMap<>();
        this.fillSubscribers = new LinkedHashMap<>();
        for (Strategy strategy : this.strategies) {
            for (Symbol symbol : strategy.symbols()) {
                klineSubscribers
                        .computeIfAbsent(new SeriesKey(symbol, strategy.interval()), key -> new ArrayList<>())
                        .add(strategy);
                fillSubscribers.computeIfAbsent(symbol, key -> new ArrayList<>()).add(strategy);
            }
        }
        if (this.strategies.isEmpty()) {
            log.warn("StrategyEngine started with no strategies - market data will be dropped");
        } else {
            log.info("StrategyEngine ready: {}", this.strategies.stream().map(Strategy::id).toList());
        }
    }

    @Override
    public void onEvent(Event event, EventPublisher publisher) {
        switch (event) {
            case KlineEvent kline -> onKline(kline, publisher);
            case FillEvent fill -> onFill(fill, publisher);
            case TimerEvent timer -> onTimer(timer, publisher);
            // Signals, order requests/updates and risk alerts are downstream of strategies.
            default -> {
            }
        }
    }

    private void onKline(KlineEvent event, EventPublisher publisher) {
        if (!event.closed()) {
            return;
        }
        SeriesKey key = new SeriesKey(event.symbol(), event.interval());
        List<Strategy> interested = klineSubscribers.get(key);
        if (interested == null) {
            return;
        }
        if (!seriesFor(event.symbol(), event.interval()).offer(event.kline())) {
            droppedBars++;
            log.debug("Dropped duplicate/out-of-order bar {} {} openTime={}",
                    event.symbol().unified(), event.interval(), event.kline().openTime());
            return;
        }
        deliveredBars++;
        // Mark before dispatching: the risk gate sizes signals against the mark price, so it must
        // already reflect the bar that produced them (and not the previous one, or nothing at all).
        portfolio.mark(event.symbol(), event.kline().close());
        for (Strategy strategy : interested) {
            invoke(strategy, () -> strategy.onKline(event, context(strategy, publisher)));
        }
    }

    private void onFill(FillEvent event, EventPublisher publisher) {
        List<Strategy> interested = fillSubscribers.get(event.symbol());
        if (interested == null) {
            return;
        }
        for (Strategy strategy : interested) {
            invoke(strategy, () -> strategy.onFill(event, context(strategy, publisher)));
        }
    }

    private void onTimer(TimerEvent event, EventPublisher publisher) {
        for (Strategy strategy : strategies) {
            invoke(strategy, () -> strategy.onTimer(event, context(strategy, publisher)));
        }
    }

    private void invoke(Strategy strategy, Runnable callback) {
        try {
            callback.run();
        } catch (Exception e) {
            log.error("Strategy {} failed, isolated from the other strategies", strategy.id(), e);
        }
    }

    private StrategyContext context(Strategy strategy, EventPublisher publisher) {
        return new DefaultStrategyContext(strategy.id(), this::seriesFor, portfolio, clock, publisher);
    }

    private BarSeries seriesFor(Symbol symbol, Interval interval) {
        return series.computeIfAbsent(new SeriesKey(symbol, interval), key -> new BarSeries(barCapacity));
    }

    /** Registered strategies, in dispatch order. */
    public List<Strategy> strategies() {
        return strategies;
    }

    public Portfolio portfolio() {
        return portfolio;
    }

    /** Closed bars appended to a series and handed to at least one strategy. */
    public long deliveredBars() {
        return deliveredBars;
    }

    /** Closed bars rejected as duplicates or out-of-order (spec edge case 1). */
    public long droppedBars() {
        return droppedBars;
    }

    private record SeriesKey(Symbol symbol, Interval interval) {
    }
}
