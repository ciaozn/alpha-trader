package com.ciaozn.alphatrader.strategy;

import com.ciaozn.alphatrader.common.event.Event;
import com.ciaozn.alphatrader.common.event.SignalEvent;
import com.ciaozn.alphatrader.common.model.Direction;
import com.ciaozn.alphatrader.common.model.Interval;
import com.ciaozn.alphatrader.common.model.Symbol;
import com.ciaozn.alphatrader.common.portfolio.Portfolio;
import com.ciaozn.alphatrader.common.time.VirtualClock;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultStrategyContextTest {

    private static final Symbol BTC = Symbol.parse("BTCUSDT.PERP");

    @Test
    void emitStampsStrategyIdAndBusinessTimeFromClock() {
        List<Event> published = new ArrayList<>();
        VirtualClock clock = new VirtualClock(1_700_000_000_000L);
        Portfolio portfolio = new Portfolio(new BigDecimal("10000"));
        Map<String, BarSeries> series = new HashMap<>();
        series.put("BTCUSDT.PERP/1h", new BarSeries(8));

        StrategyContext context = new DefaultStrategyContext(
                "ma-cross-1",
                (symbol, interval) -> series.get(symbol.unified() + "/" + interval.binanceCode()),
                portfolio, clock, published::add);

        context.emit(BTC, Direction.LONG, 0.75, "SMA10 crossed above SMA30");

        assertThat(published).hasSize(1);
        SignalEvent signal = (SignalEvent) published.get(0);
        assertThat(signal.strategyId()).isEqualTo("ma-cross-1");
        assertThat(signal.symbol()).isEqualTo(BTC);
        assertThat(signal.direction()).isEqualTo(Direction.LONG);
        assertThat(signal.strength()).isEqualTo(0.75);
        assertThat(signal.reason()).isEqualTo("SMA10 crossed above SMA30");
        // business time comes from the injected clock, never from the wall clock (FR-EN-03)
        assertThat(signal.timestamp()).isEqualTo(1_700_000_000_000L);
        assertThat(signal.eventId()).isPositive();
    }

    @Test
    void exposesBarsPortfolioAndClockOnly() {
        BarSeries bars = new BarSeries(4);
        Portfolio portfolio = new Portfolio(new BigDecimal("5000"));
        VirtualClock clock = new VirtualClock(42L);
        StrategyContext context = new DefaultStrategyContext(
                "rsi-1", (symbol, interval) -> bars, portfolio, clock, event -> {
        });

        assertThat(context.bars(BTC, Interval.H1)).isSameAs(bars);
        assertThat(context.portfolio()).isSameAs(portfolio);
        assertThat(context.clock().nowMillis()).isEqualTo(42L);
    }

    @Test
    void emitAcceptsPreBuiltSignal() {
        List<Event> published = new ArrayList<>();
        StrategyContext context = new DefaultStrategyContext(
                "rsi-1", (symbol, interval) -> new BarSeries(4),
                new Portfolio(new BigDecimal("5000")), new VirtualClock(1L), published::add);

        SignalEvent signal = SignalEvent.of("rsi-1", BTC, Direction.FLAT, 1.0, "overbought", 1L);
        context.emit(signal);

        assertThat(published).containsExactly(signal);
    }
}
