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
    private final BlockingQueue<Runnable> loopTasks = new LinkedBlockingQueue<>();
    private final PriorityBlockingQueue<ScheduledTimer> timers = new PriorityBlockingQueue<>();
    private final List<EventHandler> handlers = new CopyOnWriteArrayList<>();
    private final ArrayDeque<Event> cascade = new ArrayDeque<>();
    private final EventJournal journal;
    private final Clock clock;
    private final Thread loopThread;
    private volatile boolean running;

    /** Guards {@link #completedThrough} and {@link #dispatching}; waiters block on it. */
    private final Object quiescenceLock = new Object();
    /** Highest event id whose round has finished. Event ids are globally monotonic. */
    private long completedThrough;
    private boolean dispatching;

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

    /**
     * Hands work to the loop thread (T318). Components that must read or write shared state - the
     * book, the order store - but need to do blocking IO first use this: gather the facts on their
     * own thread, then apply them here.
     *
     * <p>This exists because "only the engine thread touches shared state" is a guarantee worth
     * keeping, and a background task that merely published events could not honour it: it would have
     * to read the book to decide what to publish. Doing the IO on the loop instead would stall every
     * strategy for a network round trip, which is worse.
     */
    public void runOnLoop(Runnable task) {
        loopTasks.offer(task);
        wakeLoop();
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
        synchronized (quiescenceLock) {
            // Release replay drivers blocked in awaitQuiescence: the loop is gone, so
            // nothing will ever make them quiescent.
            quiescenceLock.notifyAll();
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

    // ------------------------------------------------------- replay barrier

    /** Default bound for {@link #awaitQuiescence(long)}; a replay round is microseconds. */
    public static final Duration DEFAULT_QUIESCENCE_TIMEOUT = Duration.ofSeconds(30);

    /** Caps a single wait so state changed off the loop thread is still noticed. */
    private static final long QUIESCENCE_POLL_MILLIS = 10L;

    /**
     * Blocks until the round for {@code eventId} has closed and the engine has nothing
     * left to do at the current clock reading.
     *
     * <p>This exists for replay drivers. A feeder that advances the {@code VirtualClock}
     * while the bar it just published is still working through signal -&gt; risk -&gt; order
     * -&gt; fill would let the next bar's events interleave with this bar's cascade: the
     * run stops being reproducible (NFR-04) and handlers can observe a clock that jumped
     * underneath them. Waiting per bar makes "one bar == one closed round" exact.
     *
     * <p>Quiescent means: the round for {@code eventId} finished, no round is in flight,
     * the cascade and external queues are empty, and no timer is due. The timer clause
     * matters because recurring settlement (e.g. funding) is driven by timers that become
     * due exactly when the feeder moves the clock.
     *
     * <p>Only meaningful against a {@code VirtualClock}, which advances solely when the
     * caller advances it. Against a wall clock "no timer due" is not a stable condition.
     *
     * @param eventId the {@link Event#eventId()} the caller published before calling
     * @return {@code true} if quiescent within the timeout; {@code false} on timeout or if
     *         the engine is stopped - a {@code false} must abort the replay, never be ignored
     */
    public boolean awaitQuiescence(long eventId) throws InterruptedException {
        return awaitQuiescence(eventId, DEFAULT_QUIESCENCE_TIMEOUT);
    }

    /** @see #awaitQuiescence(long) */
    public boolean awaitQuiescence(long eventId, Duration timeout) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        synchronized (quiescenceLock) {
            while (running) {
                if (completedThrough >= eventId && isIdle()) {
                    return true;
                }
                // Round up: truncating would give up before the deadline, and wait(0) means forever.
                long remainingMillis = (deadlineNanos - System.nanoTime() + 999_999L) / 1_000_000L;
                if (remainingMillis <= 0) {
                    return false;
                }
                // Bounded wait: queue/clock changes made off the loop thread do not notify.
                quiescenceLock.wait(Math.min(remainingMillis, QUIESCENCE_POLL_MILLIS));
            }
            return false;
        }
    }

    /**
     * Caller must hold {@link #quiescenceLock}. No cascade check: {@link #dispatching}
     * spans the whole round, and a round is defined to include its cascade closure.
     */
    private boolean isIdle() {
        return !dispatching && externalQueue.isEmpty() && millisUntilNextTimer() > 0;
    }

    /** Work handed in by other threads (see {@link #runOnLoop}) runs on the loop, before the next event. */
    private void drainTasks() {
        Runnable task;
        while ((task = loopTasks.poll()) != null) {
            try {
                task.run();
            } catch (Exception e) {
                log.error("Loop task failed", e);
            }
        }
    }

    // ------------------------------------------------------------------ loop

    private void runLoop() {
        while (running) {
            try {
                drainTasks();
                Event event = pollNext();
                if (event != null) {
                    dispatchRound(event);
                }
            } catch (InterruptedException e) {
                // Woken by wakeLoop() (new timer scheduled) or stop() (running=false
                // ends the loop). Either way: just re-evaluate.
            } catch (Exception e) {
                // A buggy handler must never kill the engine loop.
                log.error("Unhandled error in event loop", e);
            }
            // Outside the catch: a wake-up must re-evaluate timers, not skip straight
            // back to polling.
            try {
                emitDueTimers();
            } catch (Exception e) {
                log.error("Unhandled error while emitting timers", e);
            }
        }
    }

    /**
     * Caps how long the loop parks. Deadlines are read from the {@link Clock}, and a
     * {@code VirtualClock} only moves when the replay driver moves it: parking until the
     * next deadline would park for hours of wall time right after the driver jumped the
     * clock, leaving an already-due timer unfired. Re-checking often is free - the poll
     * returns the moment an event arrives.
     */
    private static final long MAX_PARK_MILLIS = 20L;

    private Event pollNext() throws InterruptedException {
        long waitMs = Math.min(millisUntilNextTimer(), MAX_PARK_MILLIS);
        return externalQueue.poll(Math.max(waitMs, 1L), TimeUnit.MILLISECONDS);
    }

    /** Dispatch one event plus its entire cascade closure within this round. */
    private void dispatchRound(Event event) {
        synchronized (quiescenceLock) {
            dispatching = true;
        }
        try {
            dispatch(event);
            Event next;
            while ((next = cascade.poll()) != null) {
                dispatch(next);
            }
        } finally {
            synchronized (quiescenceLock) {
                dispatching = false;
                quiescenceLock.notifyAll();
            }
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
        synchronized (quiescenceLock) {
            if (event.eventId() > completedThrough) {
                completedThrough = event.eventId();
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
