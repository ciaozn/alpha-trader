package com.ciaozn.alphatrader.execution;

import com.ciaozn.alphatrader.common.event.FillEvent;

import java.util.List;
import java.util.Optional;

/**
 * Durable storage for the two execution facts FR-OP-04 names - orders and fills - and the read side
 * FR-EX-02 (idempotency) and FR-EX-04 (reconciliation) need.
 *
 * <p>The contract lives in alpha-execution next to the OMS that owns it, with nothing but the domain
 * model behind it: no Spring, no JDBC, no driver. That is what lets the state machine be written and
 * tested before a database exists, and what keeps the JDBC implementation an alpha-app concern - the
 * same split {@code KlineRepository} / {@code JdbcKlineRepository} already makes (取舍 5). A module
 * that could only be exercised against SQLite would have its order lifecycle tested by whichever
 * dialect happened to be on the classpath.
 *
 * <p><b>The two tables have different shapes, and the methods show it.</b> {@code orders} is state:
 * one row per {@code clientOrderId}, replaced on every migration, which is why the read is
 * {@link #find} and {@link #findOpen} rather than a range. {@code fills} is history: append-only,
 * which is why its read asks which order the fills belong to. Persisting a migration as a new order
 * row would leave {@code findOpen()} reporting an order as both resting and filled.
 *
 * <p><b>A fill is stored as the {@link FillEvent} itself.</b> It is an immutable fact that already
 * carries everything the row needs - which order, which symbol, which side, at what price, how much,
 * what it cost - and a second type describing the same fact is a second place to get it wrong. An
 * order cannot be stored that way because no single event in its life describes its state.
 *
 * <p>Implementations own four invariants, because everything above them relies on them:
 * <ul>
 *   <li>{@link #save} replaces the row held for that {@code clientOrderId} and does not move it: an
 *       order comes back in the order it was first saved, so a listing is deterministic (NFR-04)
 *       rather than ordered by whichever order happened to trade last;</li>
 *   <li>prices and quantities survive with their scale intact - {@code 1.50} comes back {@code 1.50},
 *       never {@code 1.5}. This is why 取舍 15 stores them as TEXT: a column type that normalizes
 *       scale changes numbers SC-02 compares bit for bit, and does it silently;</li>
 *   <li>returned lists are unmodifiable snapshots, so a caller cannot edit the store by editing what
 *       it was handed;</li>
 *   <li>nothing returns null where "none" is meant: {@link #find} is an {@code Optional} and the
 *       list reads are empty. "I have never sent this order" has to be distinguishable from "I
 *       cannot tell", or FR-EX-02's idempotency check has no negative answer to give.</li>
 * </ul>
 *
 * <p><b>Threading.</b> Written on the event-engine thread only, exactly like {@code Portfolio} - one
 * thread is what makes the book and its audit trail agree without locking. An implementation that
 * must also serve reads from another thread (the monitoring endpoint, a reconciliation timer) owns
 * that synchronization; the contract deliberately does not promise it, because promising it would
 * invite the in-memory and the JDBC implementations to be correct for different reasons.
 */
public interface OrderStore {

    /** Writes the order's current state, replacing any row already held for its clientOrderId. */
    void save(OrderRecord order);

    /** Appends one execution to its order's fill history. */
    void saveFill(FillEvent fill);

    /** The row for one order, or empty if this process has never held it (FR-EX-02). */
    Optional<OrderRecord> find(String clientOrderId);

    /** Every order the exchange could still change, in first-saved order (FR-EX-04). */
    List<OrderRecord> findOpen();

    /** One order's executions in the order they were stored; empty for an order that never filled. */
    List<FillEvent> fills(String clientOrderId);
}
