package com.ciaozn.alphatrader.strategy;

import com.ciaozn.alphatrader.common.event.SignalEvent;
import com.ciaozn.alphatrader.common.model.Direction;
import com.ciaozn.alphatrader.common.model.Interval;
import com.ciaozn.alphatrader.common.model.Symbol;
import com.ciaozn.alphatrader.common.portfolio.Portfolio;
import com.ciaozn.alphatrader.common.time.Clock;

/**
 * Everything a strategy is allowed to touch (FR-ST-05): its own bar window, a read-only
 * view of positions, the injected clock and the signal exit. Created per dispatch by
 * {@link StrategyEngine}; never hold on to an instance across callbacks.
 */
public interface StrategyContext {

    /**
     * Closed bars for one symbol/interval, oldest to newest, capped at a fixed capacity.
     * Duplicate and out-of-order pushes are dropped inside the series (spec edge case 1),
     * so a repeated k-line cannot produce a repeated signal.
     */
    BarSeries bars(Symbol symbol, Interval interval);

    /** Positions and equity. Strategies read it to know whether they are already in the market. */
    Portfolio portfolio();

    /** The only legal source of "now": virtual in backtest, wall clock in live (FR-EN-03). */
    Clock clock();

    /** Emits a fully-formed signal into the current cascade round. */
    void emit(SignalEvent signal);

    /** Convenience: stamps the owning strategy's id and the clock time onto the signal. */
    void emit(Symbol symbol, Direction direction, double strength, String reason);
}
