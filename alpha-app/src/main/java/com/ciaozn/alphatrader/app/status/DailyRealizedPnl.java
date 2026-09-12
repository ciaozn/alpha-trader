package com.ciaozn.alphatrader.app.status;

import com.ciaozn.alphatrader.common.event.Event;
import com.ciaozn.alphatrader.common.model.Money;
import com.ciaozn.alphatrader.common.portfolio.Portfolio;
import com.ciaozn.alphatrader.common.time.Clock;
import com.ciaozn.alphatrader.engine.EventHandler;
import com.ciaozn.alphatrader.engine.EventPublisher;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Realized P&L since the start of the UTC trading day (T402, FR-OP-02).
 *
 * <p>The book keeps only a cumulative total ({@link Portfolio#realizedPnl()}), so "today" is recovered as
 * a delta from a baseline - the same trick {@code CircuitBreaker} uses to recover one trade's result, and
 * for the same reason: the answer (<em>this trade</em>, <em>this day</em>) is a difference between two
 * cumulative readings, and keeping a second per-day counter would be a second ledger that can disagree
 * with the first.
 *
 * <p><b>Why the baseline is captured by {@link #onEvent} and not by whoever eventually calls
 * {@link #today()}.</b> A roll performed lazily inside a read is one amend too late: any fill that arrived
 * between midnight and that first read - every one of them lands on an event, and this handler sees every
 * event - would already be inside the cumulative total when the baseline was set, and would therefore be
 * invisible in the number that is supposed to include it. So the handler keeps the last total it observed
 * (<em>before</em> this round's own fills, because it is registered last) and rolls from that. The read
 * itself does nothing but subtract - which is also why it can answer "zero" for a day nobody has observed
 * yet, since nothing can be realized on this side without an event being dispatched first.
 *
 * <p><b>UTC, not local.</b> The breaker measures the daily drawdown over the UTC day, reconciliation and
 * the exchange settle in UTC, and whoever reads the status page may be anywhere; a day that depended on
 * the JVM's timezone would make today's result depend on where the process runs.
 *
 * <p>Reads the portfolio, so it is engine-thread only: {@link StatusSnapshotFactory} calls {@link #today()}
 * from inside a loop task and nowhere else.
 */
public final class DailyRealizedPnl implements EventHandler {

    private final Portfolio portfolio;
    private final Clock clock;
    private LocalDate currentDay;
    /** Cumulative realized at the first observed instant of {@link #currentDay}. */
    private BigDecimal dayStartRealized;
    /** Cumulative realized as of the most recent observation - the end of a day once it is over. */
    private BigDecimal lastObservedRealized;

    public DailyRealizedPnl(Portfolio portfolio, Clock clock) {
        if (portfolio == null) {
            throw new IllegalArgumentException("portfolio must not be null: today's P&L is a delta read off it");
        }
        if (clock == null) {
            throw new IllegalArgumentException("clock must not be null: 'today' is decided against the"
                    + " injected clock, never the wall clock (FR-EN-03)");
        }
        this.portfolio = portfolio;
        this.clock = clock;
        // Anything realized before this object existed belongs to whoever earned it; starting from zero
        // would attribute a pre-registration fill to today.
        this.currentDay = utcDay(clock.nowMillis());
        this.dayStartRealized = portfolio.realizedPnl();
        this.lastObservedRealized = this.dayStartRealized;
    }

    @Override
    public void onEvent(Event event, EventPublisher publisher) {
        observe(clock.nowMillis());
    }

    /** Realized P&L since midnight UTC. Negative numbers are what they look like. */
    public BigDecimal today() {
        if (!utcDay(clock.nowMillis()).equals(currentDay)) {
            // Nothing has been observed on this day, and nothing can be realized without an event being
            // dispatched - so reporting yesterday's total, which is what subtracting the old baseline
            // would do, would be a lie about a day that has not started trading yet.
            return Money.zero();
        }
        return portfolio.realizedPnl().subtract(dayStartRealized);
    }

    private void observe(long nowMillis) {
        LocalDate observedDay = utcDay(nowMillis);
        BigDecimal realized = portfolio.realizedPnl();
        if (!observedDay.equals(currentDay)) {
            currentDay = observedDay;
            dayStartRealized = lastObservedRealized;
        }
        lastObservedRealized = realized;
    }

    private static LocalDate utcDay(long epochMillis) {
        return Instant.ofEpochMilli(epochMillis).atZone(ZoneOffset.UTC).toLocalDate();
    }
}
