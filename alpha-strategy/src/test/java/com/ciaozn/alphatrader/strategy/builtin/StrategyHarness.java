package com.ciaozn.alphatrader.strategy.builtin;

import com.ciaozn.alphatrader.common.event.KlineEvent;
import com.ciaozn.alphatrader.common.event.SignalEvent;
import com.ciaozn.alphatrader.common.model.Interval;
import com.ciaozn.alphatrader.common.model.Kline;
import com.ciaozn.alphatrader.common.model.Symbol;
import com.ciaozn.alphatrader.common.portfolio.Portfolio;
import com.ciaozn.alphatrader.common.time.VirtualClock;
import com.ciaozn.alphatrader.strategy.BarSeries;
import com.ciaozn.alphatrader.strategy.DefaultStrategyContext;
import com.ciaozn.alphatrader.strategy.Strategy;
import com.ciaozn.alphatrader.strategy.StrategyContext;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Test harness that plays the part of {@code StrategyEngine}: it advances a virtual clock,
 * keeps the bar windows and records every emitted signal. Strategies are tested through the
 * exact same SPI contract they see in production.
 */
final class StrategyHarness {

    final Portfolio portfolio = new Portfolio(new BigDecimal("10000"));
    final VirtualClock clock = new VirtualClock(0L);
    final List<SignalEvent> signals = new ArrayList<>();
    private final Map<String, BarSeries> series = new LinkedHashMap<>();

    StrategyContext context(String strategyId) {
        return new DefaultStrategyContext(strategyId, this::seriesFor, portfolio, clock, event -> {
            if (event instanceof SignalEvent signal) {
                signals.add(signal);
            } else {
                throw new AssertionError("A strategy may only emit signals, got " + event);
            }
        });
    }

    private BarSeries seriesFor(Symbol symbol, Interval interval) {
        return series.computeIfAbsent(symbol.unified() + "/" + interval.binanceCode(), key -> new BarSeries(512));
    }

    /** Feeds one closed bar with a symmetric range around the close. */
    void feed(Strategy strategy, Symbol symbol, long openTime, double close) {
        feed(strategy, symbol, openTime, close + 1.0, close - 1.0, close);
    }

    void feed(Strategy strategy, Symbol symbol, long openTime, double high, double low, double close) {
        KlineEvent event = closedKline(symbol, strategy.interval(), openTime, high, low, close);
        clock.advanceTo(event.timestamp());
        seriesFor(symbol, strategy.interval()).offer(event.kline());
        strategy.onKline(event, context(strategy.id()));
    }

    void feedCloses(Strategy strategy, Symbol symbol, long firstOpenTime, double... closes) {
        long step = strategy.interval().duration().toMillis();
        for (int i = 0; i < closes.length; i++) {
            feed(strategy, symbol, firstOpenTime + i * step, closes[i]);
        }
    }

    static KlineEvent closedKline(Symbol symbol, Interval interval, long openTime,
                                  double high, double low, double close) {
        long closeTime = openTime + interval.duration().toMillis() - 1;
        Kline kline = new Kline(openTime, BigDecimal.valueOf(close), BigDecimal.valueOf(high),
                BigDecimal.valueOf(low), BigDecimal.valueOf(close), BigDecimal.ONE, closeTime);
        return KlineEvent.of(symbol, interval, kline, true, closeTime);
    }
}
