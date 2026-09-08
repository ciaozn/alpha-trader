package com.ciaozn.alphatrader.engine;

import com.ciaozn.alphatrader.common.event.Event;
import com.ciaozn.alphatrader.common.event.TimerEvent;
import com.ciaozn.alphatrader.common.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * The heart of the system: a single-threaded event loop (FR-EN-01).
 *
 * <p>Ordering guarantees:
 * <ol>
 *   <li>External events are processed FIFO.</li>
 *   <li>Every event is journaled BEFORE handlers see it (crash -> replay).</li>
 *   <li>Events published by handlers during dispatch join the cascade queue and are
 *       fully processed within the same round, before the next external event -
 *       one k-line's entire signal->risk->order chain closes in a single round.</li>
 *   <li>Due timers fire between rounds, never mid-dispatch.</li>
 * </ol>
 *
 * <p>Determinism (NFR-04): with a VirtualClock and a fixed input sequence, the entire
 * dispatch sequence is reproducible bit-for-bit.
 */
public final class EventEngine implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(EventEngine.class);

    private final BlockingQueue<Event> externalQueue = new LinkedBlockingQueue<>();
    private final PriorityBlockingQueue<ScheduledTimer> timers = new PriorityBlockingQueue<>();
    private final List<EventHandler> handlers = new CopyOnWriteArrayList<>();
    private final ArrayDeque<Event> cascade = new ArrayDeque<>();
    private final EventJournal journal;
    private final Clock clock;
    private final Thread loopThread;
    private volatile boolean running;

    public EventEngine(EventJournal journal, Clock clock) {
        this.journal = journal;
        this.clock = clock;
        this.loopThread = new Thread(this::runLoop, "event-engine");
        // Non-daemon: the engine IS the application's reason to stay alive.
        // JVM exits only after engine.stop() (Spring destroy hook) completes.
        this.loopThread.setDaemon(false);
    }

    public void registerHandler(EventHandler handler) {
        handlers.add(handler);
    }

    /** Thread-safe entry point for external producers (gateways, feeders, REST). */
    public void publish(Event event) {
        externalQueue.offer(event);
    }

    /** Schedule a repeating timer (reconciliation, heartbeat...). Fires as TimerEvent. */
    public void scheduleRepeating(String name, Duration period) {
        timers.offer(new ScheduledTimer(name, period.toMillis(), clock.nowMillis() + period.toMillis()));
        wakeLoop();
    }

    /** Schedule a one-shot timer. */
    public void scheduleOnce(String name, Duration delay) {
        timers.offer(new ScheduledTimer(name, 0L, clock.nowMillis() + delay.toMillis()));
        wakeLoop();
    }

    /**
     * The loop may be parked indefinitely on the external queue; a newly scheduled
     * timer must wake it so it re-evaluates the wait timeout. Interrupting is safe:
     * the loop treats interrupts as "re-check state", only stop() flips running=false.
     */
    private void wakeLoop() {
        loopThread.interrupt();
    }

    public void start() {
        if (running) {
            return;
        }
        running = true;
        loopThread.start();
        log.info("EventEngine started");
    }

    public void stop() {
        running = false;
        loopThread.interrupt();
        try {
            loopThread.join(TimeUnit.SECONDS.toMillis(5));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        journal.close();
        log.info("EventEngine stopped");
    }

    @Override
    public void close() {
        stop();
    }

    public boolean isRunning() {
        return running && loopThread.isAlive();
    }

    // ------------------------------------------------------------------ loop

    private void runLoop() {
        while (running) {
            try {
                Event event = pollNext();
                if (event != null) {
                    dispatchRound(event);
                }
                emitDueTimers();
            } catch (InterruptedException e) {
                // Woken by wakeLoop() (new timer scheduled) or stop() (running=false
                // ends the loop). Either way: just re-evaluate.
            } catch (Exception e) {
                // A buggy handler must never kill the engine loop.
                log.error("Unhandled error in event loop", e);
            }
        }
    }

    private Event pollNext() throws InterruptedException {
        long waitMs = millisUntilNextTimer();
        if (waitMs == Long.MAX_VALUE) {
            return externalQueue.take();
        }
        return externalQueue.poll(Math.max(waitMs, 1L), TimeUnit.MILLISECONDS);
    }

    /** Dispatch one event plus its entire cascade closure within this round. */
    private void dispatchRound(Event event) {
        dispatch(event);
        Event next;
        while ((next = cascade.poll()) != null) {
            dispatch(next);
        }
    }

    private void dispatch(Event event) {
        journal.append(event);
        for (EventHandler handler : handlers) {
            try {
                handler.onEvent(event, cascade::add);
            } catch (Exception e) {
                log.error("Handler {} failed on {}", handler.getClass().getSimpleName(), event, e);
            }
        }
    }

    private long millisUntilNextTimer() {
        ScheduledTimer next = timers.peek();
        if (next == null) {
            return Long.MAX_VALUE;
        }
        return next.nextTriggerMillis - clock.nowMillis();
    }

    private void emitDueTimers() {
        long now = clock.nowMillis();
        while (true) {
            ScheduledTimer next = timers.peek();
            if (next == null || next.nextTriggerMillis > now) {
                return;
            }
            timers.poll();
            dispatchRound(TimerEvent.of(next.name, now));
            if (next.periodMillis > 0) {
                timers.offer(new ScheduledTimer(next.name, next.periodMillis, clock.nowMillis() + next.periodMillis));
            }
            now = clock.nowMillis();
        }
    }

    private record ScheduledTimer(String name, long periodMillis, long nextTriggerMillis)
            implements Comparable<ScheduledTimer> {
        @Override
        public int compareTo(ScheduledTimer other) {
            return Long.compare(this.nextTriggerMillis, other.nextTriggerMillis);
        }
    }
}
