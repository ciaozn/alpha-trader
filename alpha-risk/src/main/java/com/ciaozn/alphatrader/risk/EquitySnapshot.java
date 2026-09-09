package com.ciaozn.alphatrader.risk;

import java.math.BigDecimal;

/**
 * Equity at one instant - FR-OP-04's 净值快照, one row per sample.
 *
 * <p><b>Deliberately one number.</b> The account breakdown at a <em>decision</em> instant is
 * {@link InterceptionRecord}'s job, and it is complete there because a rule's verdict is only
 * arguable against the numbers it saw. A snapshot table that also carried cash, notional and P&amp;L
 * would be a second book written on a timer: it would agree with {@code Portfolio} most of the time,
 * disagree whenever a fill landed between the two writes, and give no way to tell which of them moved.
 * One book, and a series of one number sampled from it.
 *
 * <p>{@code businessTs} is the replay or exchange timestamp, never the wall clock, so the same run
 * produces the same rows (NFR-04) - the same distinction {@code EquityRecorder} already makes between
 * the point's timestamp and the moment it was taken.
 */
public record EquitySnapshot(long businessTs, BigDecimal equity) {
}
