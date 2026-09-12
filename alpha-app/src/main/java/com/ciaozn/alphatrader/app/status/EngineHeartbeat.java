package com.ciaozn.alphatrader.app.status;

import com.ciaozn.alphatrader.common.event.Event;
import com.ciaozn.alphatrader.common.time.Clock;
import com.ciaozn.alphatrader.engine.EventHandler;
import com.ciaozn.alphatrader.engine.EventPublisher;

/**
 * The engine's pulse, as {@code /api/status} reports it (T402, FR-OP-02).
 *
 * <p>Why this needs to exist at all: {@code EventEngine#isRunning()} says whether the loop thread is
 * alive, which is not the same as whether the engine is <em>doing</em> anything. A process whose gateway
 * died but whose loop is parked waiting for events reports alive to anything that asks it, and that is
 * exactly the state an operator needs told apart from "up and trading" - every monitoring check that has
 * ever reported green during an outage did so because it could only see the thread, not the work.
 *
 * <p><b>Registered last</b> (see {@code StatusWiring}) so that "an event was seen" means it went all the
 * way through marks, recorder, gate, OMS and strategies: a heartbeat counting events at the front of the
 * line would read healthy while the one handler that was supposed to act on them sat broken.
 *
 * <p>The two counters are {@code volatile} because {@code /api/status} answers on a servlet thread while
 * they are written on the loop thread. Nothing here is synchronized - each field is written by exactly
 * one thread and read as a whole - and nothing here reads anyone else's state: had it read the portfolio
 * to report equity, it would have been reading engine-thread-owned state from another thread, which is
 * why {@link StatusSnapshotFactory} does all of its reading inside a loop task.
 */
public final class EngineHeartbeat implements EventHandler {

    /** Not observed yet; deliberately not 0, which is a real instant that the epoch contains. */
    public static final long NEVER = -1L;

    private final Clock clock;
    private volatile long lastEventMillis = NEVER;
    private volatile long eventsObserved;

    public EngineHeartbeat(Clock clock) {
        this.clock = clock;
    }

    @Override
    public void onEvent(Event event, EventPublisher publisher) {
        // The injected clock, not the event's business timestamp: what this measures is when the loop
        // got to the event, and in online modes that IS wall time. Using timestamp() would report the
        // exchange's time for the last bar, which stays frozen while the loop itself is dead.
        lastEventMillis = clock.nowMillis();
        eventsObserved++;
    }

    /**
     * @param nowMillis taken at read time, so {@code sinceLastEvent} is an age rather than a stored field
     *                  that quietly rots if nobody polls
     */
    public Reading read(long nowMillis) {
        long last = lastEventMillis;
        Long stamped = last == NEVER ? null : last;
        return new Reading(stamped, stamped == null ? null : nowMillis - last, eventsObserved);
    }

    /** @param lastEventMillis   {@code null} before the first event reaches it */
    public record Reading(Long lastEventMillis, Long sinceLastEventMillis, long eventsObserved) {
    }
}
