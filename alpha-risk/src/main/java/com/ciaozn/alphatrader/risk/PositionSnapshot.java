package com.ciaozn.alphatrader.risk;

import com.ciaozn.alphatrader.common.portfolio.Position;

import java.math.BigDecimal;

/**
 * What the book held in one symbol at one instant, and what that symbol was marked at - 取舍 15's
 * fifth table, added because FR-EX-04/FR-EX-06 let reconciliation <em>correct</em> local positions
 * and a correction that leaves no trace is indistinguishable from a bug that moved the book.
 *
 * <p><b>The mark price is stored with the position because it is the one input not recoverable from
 * the row.</b> {@code qty} and {@code entryPrice} say what was held; only the mark says what it was
 * worth, and re-valuing a past position at a later mark is how a position history quietly starts
 * lying - every row still looks like data, and the notional that breached a cap the day it happened
 * no longer does.
 *
 * <p>The store keeps whatever it is handed, so whether flat symbols get a row is the sampler's
 * decision, and it is the difference between a table that grows with the symbol list and one that
 * grows with the trading. {@code Portfolio.openPositions()} is the honest default.
 */
public record PositionSnapshot(long businessTs, Position position, BigDecimal markPrice) {
}
