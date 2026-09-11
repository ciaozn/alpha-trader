package com.ciaozn.alphatrader.execution;

import com.ciaozn.alphatrader.common.event.Event;
import com.ciaozn.alphatrader.common.portfolio.Portfolio;
import com.ciaozn.alphatrader.common.time.Clock;
import com.ciaozn.alphatrader.engine.EventEngine;
import com.ciaozn.alphatrader.gateway.ExchangeGateway;
import com.ciaozn.alphatrader.gateway.ExchangeUnreachableException;
import com.ciaozn.alphatrader.gateway.OpenOrder;
import com.ciaozn.alphatrader.gateway.Position;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Runs {@link Reconciler} on a timer (T318, FR-EX-04): pulls the exchange's view of orders, positions
 * and account, and publishes whatever corrections come back.
 *
 * <p><b>A thread of its own, deliberately not an engine timer.</b> These are three blocking REST
 * calls, and running them on the event loop would stall every strategy for a network round trip. It
 * therefore cannot touch the book either: it gathers the exchange's facts on this thread, then hands
 * the comparison to {@code engine.runOnLoop}, so {@link Reconciler} reads and writes shared state on
 * the same single thread as the rest of the system. The alternative - a background thread writing to
 * the book while strategies read it - is a race that no amount of care makes safe.
 *
 * <p><b>A full pass at startup, then on the timer</b> (spec edge case 7): a process that restarts with
 * a database full of open orders must find out within one second whether those orders still exist,
 * not within one period.
 *
 * <p>An unreachable exchange is logged and tolerated: reconciliation is a periodic correction, and a
 * missed pass is caught by the next one. It is NOT reported as "nothing is wrong" - no events are
 * published, so whatever the local state believes stays unconfirmed rather than confirmed.
 */
public final class ReconciliationRunner implements AutoCloseable {

    /** Spec default: every 60 seconds. */
    public static final Duration DEFAULT_PERIOD = Duration.ofSeconds(60);

    private static final Logger log = LoggerFactory.getLogger(ReconciliationRunner.class);

    private final ExchangeGateway gateway;
    private final Reconciler reconciler;
    private final EventEngine engine;
    private final Duration period;
    private final ScheduledExecutorService scheduler;

    public ReconciliationRunner(ExchangeGateway gateway, Reconciler reconciler, EventEngine engine) {
        this(gateway, reconciler, engine, DEFAULT_PERIOD);
    }

    public ReconciliationRunner(ExchangeGateway gateway, Reconciler reconciler, EventEngine engine,
                                Duration period) {
        this.gateway = gateway;
        this.reconciler = reconciler;
        this.engine = engine;
        this.period = period;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "reconciliation");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void start() {
        runOnce();
        scheduler.scheduleAtFixedRate(this::runSafely, period.toMillis(), period.toMillis(),
                TimeUnit.MILLISECONDS);
        log.info("Reconciliation scheduled every {} s", period.toSeconds());
    }

    /** One pass. Exposed so tests and startup can drive it without waiting for the timer. */
    public void runOnce() {
        // The three queries block, so they happen here; applying the result touches the book and
        // therefore happens on the loop thread, where every other write to it happens.
        Reconciler.ExchangeState state = new Reconciler.ExchangeState(
                gateway.queryOpenOrders(), gateway.queryPositions(), gateway.queryAccount());
        engine.runOnLoop(() -> publish(reconciler.reconcile(state)));
    }

    private void publish(List<Event> events) {
        for (Event event : events) {
            engine.publish(event);
        }
    }

    private void runSafely() {
        try {
            runOnce();
        } catch (ExchangeUnreachableException e) {
            log.warn("Reconciliation pass skipped, exchange unreachable: {}", e.getMessage());
        } catch (RuntimeException e) {
            log.error("Reconciliation pass failed", e);
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
        log.info("Reconciliation stopped");
    }
}
