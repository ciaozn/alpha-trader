package com.ciaozn.alphatrader.risk;

import com.ciaozn.alphatrader.common.event.SignalEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToLongFunction;

/**
 * The {@link RecordStore} that does no I/O: what the gate, the sampler and the OMS are tested
 * against, and what a mode that runs without a business database is given.
 *
 * <p>Keeps everything and forgets nothing, so a long run grows without bound, and every read is a
 * linear scan - both fine for a test double, both the reason the JDBC implementation has indexes.
 *
 * <p>Insertion-ordered throughout (NFR-04): four append-only lists and no map keyed by a domain type,
 * so a query's answer depends on the order facts arrived and on nothing else. Not synchronized, per
 * the contract's threading rule.
 */
public final class InMemoryRecordStore implements RecordStore {

    private final List<SignalEvent> signals = new ArrayList<>();
    private final List<InterceptionRecord> interceptions = new ArrayList<>();
    private final List<EquitySnapshot> equity = new ArrayList<>();
    private final List<PositionSnapshot> positions = new ArrayList<>();

    @Override
    public void saveSignal(SignalEvent signal) {
        signals.add(signal);
    }

    @Override
    public void saveInterception(InterceptionRecord interception) {
        interceptions.add(interception);
    }

    @Override
    public void saveEquitySnapshot(EquitySnapshot snapshot) {
        equity.add(snapshot);
    }

    @Override
    public void savePosition(PositionSnapshot position) {
        positions.add(position);
    }

    @Override
    public List<SignalEvent> signals(long from, long to) {
        return inRange(signals, from, to, SignalEvent::timestamp);
    }

    @Override
    public List<InterceptionRecord> interceptions(String ruleId) {
        return interceptions.stream().filter(record -> record.ruleId().equals(ruleId)).toList();
    }

    @Override
    public List<InterceptionRecord> interceptions(long from, long to) {
        return inRange(interceptions, from, to, InterceptionRecord::timestamp);
    }

    @Override
    public List<EquitySnapshot> equitySnapshots(long from, long to) {
        return inRange(equity, from, to, EquitySnapshot::businessTs);
    }

    @Override
    public List<PositionSnapshot> positions(long from, long to) {
        return inRange(positions, from, to, PositionSnapshot::businessTs);
    }

    private static <T> List<T> inRange(List<T> rows, long from, long to, ToLongFunction<T> timestamp) {
        return rows.stream().filter(row -> {
            long at = timestamp.applyAsLong(row);
            return at >= from && at <= to;
        }).toList();
    }
}
