package com.ciaozn.alphatrader.strategy;

import com.ciaozn.alphatrader.common.event.SignalEvent;
import com.ciaozn.alphatrader.common.model.Direction;
import com.ciaozn.alphatrader.common.model.Interval;
import com.ciaozn.alphatrader.common.model.Symbol;
import com.ciaozn.alphatrader.common.portfolio.Portfolio;
import com.ciaozn.alphatrader.common.time.Clock;
import com.ciaozn.alphatrader.engine.EventPublisher;

import java.util.function.BiFunction;

/**
 * The context handed to a strategy for one callback. It exists to make the FR-ST-05
 * boundary physical: the strategy receives bar windows, positions and a clock, and the only
 * way out is {@link #emit} - there is no gateway, no OMS and no order API in scope.
 *
 * <p>Signals go to the {@link EventPublisher} of the current dispatch, i.e. into the cascade
 * queue, so the whole signal -&gt; risk -&gt; order chain of one bar closes in the same engine
 * round (FR-EN-01).
 */
public final class DefaultStrategyContext implements StrategyContext {

    private final String strategyId;
    private final BiFunction<Symbol, Interval, BarSeries> seriesLookup;
    private final Portfolio portfolio;
    private final Clock clock;
    private final EventPublisher publisher;

    public DefaultStrategyContext(String strategyId,
                                 BiFunction<Symbol, Interval, BarSeries> seriesLookup,
                                 Portfolio portfolio,
                                 Clock clock,
                                 EventPublisher publisher) {
        this.strategyId = strategyId;
        this.seriesLookup = seriesLookup;
        this.portfolio = portfolio;
        this.clock = clock;
        this.publisher = publisher;
    }

    @Override
    public BarSeries bars(Symbol symbol, Interval interval) {
        return seriesLookup.apply(symbol, interval);
    }

    @Override
    public Portfolio portfolio() {
        return portfolio;
    }

    @Override
    public Clock clock() {
        return clock;
    }

    @Override
    public void emit(SignalEvent signal) {
        publisher.publish(signal);
    }

    @Override
    public void emit(Symbol symbol, Direction direction, double strength, String reason) {
        publisher.publish(SignalEvent.of(strategyId, symbol, direction, strength, reason, clock.nowMillis()));
    }

    public String strategyId() {
        return strategyId;
    }
}
