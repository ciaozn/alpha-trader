package com.ciaozn.alphatrader.app.status;

import java.math.BigDecimal;
import java.util.List;

/**
 * What {@code /api/status} returns (T402, FR-OP-02): everything an operator asks first - where the money
 * is, whether the loop is working, whether the exchange is answering, which mode this process thinks it
 * is running in.
 *
 * <p><b>Immutable, and read in one go.</b> Every field is what the loop knew at a single instant,
 * because {@link StatusSnapshotFactory} builds the whole object inside one task on the engine thread -
 * the same snapshot discipline {@code SignalFacts} exists to give the risk gate. A dto assembled field by
 * field on a servlet thread would mix numbers from different loops and present them as one state of the
 * world, and the mixtures that result (equity from before a fill, positions from after) are precisely the
 * ones that make somebody distrust the page.
 *
 * <p><b>Money stays {@link BigDecimal}</b>, never {@code double}, for the reason everywhere else in this
 * system: these numbers have to agree with the backtest to the cent or the backtest stops being evidence
 * (FR-BT-06), and a rounded inferior can only lose that agreement silently.
 *
 * <p><b>Every section survives being unavailable.</b> A degraded snapshot still answers with the parts it
 * can account for and says why the rest is missing; {@code healthy} is the single summary for anything
 * that polls this endpoint, so that "the page loaded" can never be mistaken for "the system is fine".
 *
 * @param mode      {@code backtest} / {@code paper} / {@code live}, from {@code alpha.mode}
 * @param healthy   nothing is known to be broken - the one boolean a poller should branch on. It is not a
 *                  claim that the loop has seen events recently: see
 *                  {@link StatusSnapshotFactory#reasonNotToTrustThis}
 * @param note      why this document is untrustworthy or partial, null when nothing is
 * @param generated when this snapshot was read, epoch millis
 */
public record StatusSnapshot(
        String mode,
        boolean healthy,
        String note,
        long generatedAtMillis,
        EngineView engine,
        GatewayView gateway,
        AccountView account,
        List<PositionView> positions) {

    /**
     * The loop, as opposed to the process.
     *
     * @param lastEventMillis      null before the first event; see {@link EngineHeartbeat}
     * @param sinceLastEventMillis age of the newest event at {@code generatedAtMillis}, null if none yet
     * @param eventsObserved       how many events the loop has dispatched since registration
     */
    public record EngineView(boolean alive, Long lastEventMillis, Long sinceLastEventMillis,
                             long eventsObserved) {
    }

    /**
     * @param latencyMillis      round trip of the last successful probe, null if there has not been one
     * @param lastProbeMillis    when that answer arrived
     * @param sinceLastProbeMillis its age at {@code generatedAtMillis}; large means the answer is stale
     * @param detail             why the exchange is not answering, null when it is
     */
    public record GatewayView(String state, boolean connected, Long latencyMillis,
                              Long lastProbeMillis, Long sinceLastProbeMillis, String detail) {
    }

    /**
     * The book, in one read.
     *
     * @param realizedPnlToday today's realized result over the UTC day; see {@code DailyRealizedPnl}
     */
    public record AccountView(BigDecimal equity,
                              BigDecimal cash,
                              BigDecimal unrealizedPnl,
                              BigDecimal realizedPnlTotal,
                              BigDecimal realizedPnlToday,
                              BigDecimal totalNotional) {
    }

    /** One open position. {@code notional} and {@code unrealizedPnl} are against the current mark. */
    public record PositionView(String symbol,
                               String direction,
                               BigDecimal qty,
                               BigDecimal entryPrice,
                               BigDecimal markPrice,
                               BigDecimal notional,
                               BigDecimal unrealizedPnl) {
    }
}
