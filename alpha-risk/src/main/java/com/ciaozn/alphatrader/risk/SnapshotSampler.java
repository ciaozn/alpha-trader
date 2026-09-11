package com.ciaozn.alphatrader.risk;

import com.ciaozn.alphatrader.common.event.Event;
import com.ciaozn.alphatrader.common.event.TimerEvent;
import com.ciaozn.alphatrader.common.portfolio.Portfolio;
import com.ciaozn.alphatrader.common.portfolio.Position;
import com.ciaozn.alphatrader.common.time.Clock;
import com.ciaozn.alphatrader.engine.EventHandler;
import com.ciaozn.alphatrader.engine.EventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Periodic sampler for the two history tables nothing else writes (T318b): {@code equity_snapshot}
 * and {@code positions}.
 *
 * <p><b>Why a sampler and not a listener.</b> Every other row in {@link RecordStore} is a fact that
 * already passed through the bus - a signal, an interception, an order. Equity is not an event: it is
 * a quantity that exists continuously and changes whenever anything happens, so the only way to have
 * a history of it is to decide how often to look. That decision is a timer, and hiding it behind a
 * listener would pretend the sampling rate was not a choice.
 *
 * <p><b>Every position row carries the mark price it was valued at.</b> That is the one input that
 * cannot be reconstructed later: revaluing an old position at a later price is how a position history
 * quietly starts lying - the notional that breached a limit yesterday stops breaching it when
 * revalued today, and a limit breach becomes indistinguishable from a rounding difference.
 *
 * <p>Only open positions are written. Writing a row for a closed symbol would fill the table with
 * zeroes and make "we held this" unanswerable; the last row for a symbol is the last moment it was
 * open.
 */
public final class SnapshotSampler implements EventHandler {

    /** Timer name this sampler answers to; the wiring schedules it under the same name. */
    public static final String TIMER = "snapshot";

    private static final Logger log = LoggerFactory.getLogger(SnapshotSampler.class);

    private final Portfolio portfolio;
    private final RecordStore records;
    private final Clock clock;

    public SnapshotSampler(Portfolio portfolio, RecordStore records, Clock clock) {
        this.portfolio = portfolio;
        this.records = records;
        this.clock = clock;
    }

    @Override
    public void onEvent(Event event, EventPublisher publisher) {
        if (event instanceof TimerEvent timer && TIMER.equals(timer.name())) {
            sample();
        }
    }

    /** Writes one equity row and one row per open position at the current clock time. */
    public void sample() {
        long now = clock.nowMillis();
        records.saveEquitySnapshot(new EquitySnapshot(now, portfolio.equity()));
        int positions = 0;
        for (Position position : portfolio.openPositions()) {
            records.savePosition(new PositionSnapshot(now, position, portfolio.markOf(position.symbol())));
            positions++;
        }
        log.debug("Snapshot at {}: equity={}, {} open position(s)", now, portfolio.equity(), positions);
    }
}
