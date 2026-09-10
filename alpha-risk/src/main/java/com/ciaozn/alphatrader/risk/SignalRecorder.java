package com.ciaozn.alphatrader.risk;

import com.ciaozn.alphatrader.common.event.Event;
import com.ciaozn.alphatrader.common.event.SignalEvent;
import com.ciaozn.alphatrader.engine.EventHandler;
import com.ciaozn.alphatrader.engine.EventPublisher;

/**
 * Writes every signal that reaches the bus to the {@code signals} table (FR-OP-04), and publishes
 * nothing.
 *
 * <p><b>A bus observer, where {@link RiskGate}'s interception row is deliberately not one.</b> The gate
 * writes its own row because half of it - the {@link SignalFacts} the rules were handed - exists only
 * inside one {@code onEvent} call and never reaches the bus, so no handler registration order could
 * recover it. A signal is the opposite case: the event on the bus is the whole fact, so an observer
 * loses nothing by not being the gate. What it gains is the signals the gate never answered for. When a
 * rule throws, {@code EventEngine.dispatch} logs it and carries on to the next handler, so the gate
 * writes neither an alert nor an interception row and leaves no trace at all; a recorder registered
 * ahead of it has already written the signal, and that row is the only evidence of what was in flight
 * when the gate died. Recording inside the gate would quietly redefine "what the strategies emitted"
 * as "what risk managed to receive".
 *
 * <p><b>Register it ahead of the gate.</b> The two tables are joined on {@code event_id}, and the order
 * decides which half of that join can dangle. Recorder first, and a death between the two writes leaves
 * a signal with no interception - an ordinary signal that was not refused. Gate first, and it leaves an
 * interception whose {@code signal_event_id} names a row that does not exist, which is a broken join in
 * exactly the query the pair of tables exists to answer.
 *
 * <p>Runs on the engine thread only, so it adds no synchronization of its own; {@link RecordStore}
 * states that contract and an implementation serving reads from another thread owns it.
 *
 * <p>A replayed journal re-dispatches a signal it already dispatched once, and this appends it again:
 * {@code signals} is keyed by an auto-increment {@code seq}, so {@code event_id} is a join key rather
 * than an identity. Deduplicating here would mean holding every event id seen for the life of the
 * process, and the same is true of the gate's interception rows - so the fix belongs to whatever replays
 * a journal into a live engine, not to the writers.
 */
public final class SignalRecorder implements EventHandler {

    private final RecordStore records;

    public SignalRecorder(RecordStore records) {
        this.records = records;
    }

    @Override
    public void onEvent(Event event, EventPublisher publisher) {
        if (event instanceof SignalEvent signal) {
            records.saveSignal(signal);
        }
    }
}
