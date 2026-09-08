package com.ciaozn.alphatrader.strategy;

import com.ciaozn.alphatrader.common.event.FillEvent;
import com.ciaozn.alphatrader.common.event.KlineEvent;
import com.ciaozn.alphatrader.common.event.TimerEvent;
import com.ciaozn.alphatrader.common.model.Interval;
import com.ciaozn.alphatrader.common.model.Symbol;

import java.util.Set;

/**
 * The strategy SPI (FR-ST-01): implement this, register a factory, enable it in
 * application.yml - the event engine, risk pipeline and OMS never change (spec scenario 6).
 *
 * <p>A strategy's ONLY output is a {@link com.ciaozn.alphatrader.common.event.SignalEvent}
 * emitted through the context (FR-ST-05). It must never place orders, never touch the
 * network, and never read system state other than the {@link com.ciaozn.alphatrader.common.time.Clock}
 * exposed by the context. That constraint is what makes backtest and live behave identically.
 *
 * <p>Callbacks run on the single event-engine thread, in registration order. They must be
 * fast and non-blocking; keep state in fields (one strategy instance = one symbol set).
 */
public interface Strategy {

    /** Stable identifier, also used as the clientOrderId prefix (FR-EX-02). */
    String id();

    /** Symbols this strategy trades; k-lines for other symbols are never delivered. */
    Set<Symbol> symbols();

    /** The single timeframe this strategy evaluates (multi-timeframe is out of scope for v1). */
    Interval interval();

    /**
     * Delivered for CLOSED bars only, in chronological order. Acting on closed bars is the
     * anti-look-ahead discipline (FR-BT-02); the forming bar is never passed to a strategy.
     */
    default void onKline(KlineEvent event, StrategyContext context) {
    }

    /** Execution feedback, for strategies that track their own fill state (FR-ST-06). */
    default void onFill(FillEvent event, StrategyContext context) {
    }

    /** Periodic tick (reconciliation, heartbeat, time-based exits). */
    default void onTimer(TimerEvent event, StrategyContext context) {
    }
}
