package com.ciaozn.alphatrader.risk;

import com.ciaozn.alphatrader.common.event.SignalEvent;

import java.util.List;

/**
 * Durable storage for the four audit facts that are not orders: signals, risk interceptions, equity
 * snapshots and position snapshots (FR-OP-04, FR-RK-08, 取舍 15). Orders and fills are
 * {@code OrderStore}'s, in alpha-execution, because the OMS owns them.
 *
 * <p><b>One interface for four tables, and it lives here because of the interception.</b> Three of
 * the four are facts already on the bus - a signal is a {@code SignalEvent}, a sample is a number at
 * an instant - and any of them could be written by whoever happens to hold the store. The fourth
 * cannot: an {@link InterceptionRecord} needs the account snapshot the rules were handed, which
 * exists only inside one gate call and is never published. So the contract that makes it storable is
 * risk's, and giving the four one owner means one DDL, one transaction boundary and one answer to
 * "what did this system record". Splitting the risk half out would leave two JDBC classes writing the
 * same file and two places to keep the schema straight.
 *
 * <p><b>All four tables are append-only history</b> - unlike {@code orders}, which is current state.
 * Nothing here is updated or replaced, so every read is a range or a filter over an insertion-ordered
 * list and a row, once written, means what it meant when it was written.
 *
 * <p>Implementations own the same four invariants {@code OrderStore} states: insertion order is
 * preserved and never depends on a hash (NFR-04); prices and quantities survive with their scale
 * intact, which is why 取舍 15 stores them as TEXT; returned lists are unmodifiable snapshots; and
 * "none" is an empty list, never null.
 *
 * <p><b>Ranges are inclusive at both ends</b>, matching {@code KlineRepository.load}, so "the
 * interceptions on this day" is one query and needs no off-by-one reasoning at the call site. The
 * consequence is that two adjacent windows share their boundary instant and a row at exactly that
 * instant appears in both - worth knowing before stitching windows together, and the reason nothing
 * here pages.
 *
 * <p>Written on the event-engine thread only; an implementation serving reads from another thread
 * owns that synchronization.
 */
public interface RecordStore {

    /** One signal a strategy emitted, whether or not anything downstream acted on it. */
    void saveSignal(SignalEvent signal);

    /** One refusal, with the rule that refused and the account it refused against (FR-RK-08). */
    void saveInterception(InterceptionRecord interception);

    /** One equity sample. */
    void saveEquitySnapshot(EquitySnapshot snapshot);

    /** One symbol's position at one instant. */
    void savePosition(PositionSnapshot position);

    /** Signals whose business timestamp falls in {@code [from, to]}, in the order they arrived. */
    List<SignalEvent> signals(long from, long to);

    /** Every refusal attributed to one rule id, in the order they happened. */
    List<InterceptionRecord> interceptions(String ruleId);

    /** Every refusal in {@code [from, to]}, in the order they happened. */
    List<InterceptionRecord> interceptions(long from, long to);

    /** Equity samples in {@code [from, to]}, in the order they were taken. */
    List<EquitySnapshot> equitySnapshots(long from, long to);

    /** Position rows in {@code [from, to]}, in the order they were sampled. */
    List<PositionSnapshot> positions(long from, long to);
}
