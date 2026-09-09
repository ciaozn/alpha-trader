package com.ciaozn.alphatrader.execution;

import com.ciaozn.alphatrader.common.event.Event;
import com.ciaozn.alphatrader.common.event.OrderReportEvent;
import com.ciaozn.alphatrader.common.event.OrderRequestEvent;
import com.ciaozn.alphatrader.common.event.RiskAlertEvent;
import com.ciaozn.alphatrader.common.model.OrderStatus;
import com.ciaozn.alphatrader.common.time.Clock;
import com.ciaozn.alphatrader.engine.EventEngine;
import com.ciaozn.alphatrader.gateway.ExchangeGateway;
import com.ciaozn.alphatrader.gateway.ExchangeUnreachableException;
import com.ciaozn.alphatrader.gateway.OrderAck;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * The send path (FR-EX-02, 边界 6). One thread of its own, because {@code EventHandler} forbids
 * network IO on the engine thread and {@code placeOrder} is a blocking REST call: sending inline would
 * stall k-lines, signals and the risk gate for as long as the exchange takes to answer.
 *
 * <p><b>Every attempt produces exactly one event</b> - either the exchange's answer as an
 * {@link OrderReportEvent}, or the statement that there was none as a {@link RiskAlertEvent}. The OMS
 * consumes both on the engine thread, so the order row is only ever moved by the component that owns
 * it, and nothing here needs the store.
 *
 * <p><b>No answer is not an answer, and this class does not retry.</b> A rejection means the exchange
 * took the request and refused it, so the order is dead; an {@link ExchangeUnreachableException} means
 * the outcome is unknown, so the order may be resting on the book. The row is left at NEW, a CRITICAL
 * alert says why, and FR-EX-04's reconciliation - the component that asks the exchange what it actually
 * holds - decides. Retrying here would be the one way to turn that into a real duplicate: a re-sent
 * {@code clientOrderId} whose first attempt did land comes back as a <em>rejection</em> for an order
 * that is live, and this class has no way to tell that rejection from a genuine one. Retrying is safe
 * only where it is known the request never reached the wire, which is inside the gateway (T316).
 *
 * <p><b>An id this process has handed over is never handed over again.</b> The set is not cleared when
 * an order is rejected, canceled or filled, and not when a send fails: once the exchange may have seen
 * an id, re-sending it is exactly what FR-EX-02 forbids. An order that genuinely has to be placed again
 * is a new order and gets a new id from {@code ClientOrderIds}.
 *
 * <p>Daemon, unlike the engine's loop thread: that one is the application's reason to stay alive, and a
 * second non-daemon thread would be a second, independent way to hang the JVM after the engine has let
 * go. {@link #close()} stops the worker and reports anything still queued rather than dropping it
 * quietly - those rows are still NEW and reconciliation will find them, but they are said out loud here
 * first.
 */
public final class OrderSender implements OrderOutbox, AutoCloseable {

    /** The exchange did not answer, so whether this order is on the book is unknown (边界 6). */
    public static final String RULE_UNREACHABLE = "EX-exchange-unreachable";
    /** Our own send path threw. The outcome is unknown for the same reason; the cause is not the same. */
    public static final String RULE_SEND_FAILED = "EX-send-failed";

    private static final Logger log = LoggerFactory.getLogger(OrderSender.class);

    /** How long the worker parks before re-checking {@link #closed}; a queued order wakes it at once. */
    private static final long POLL_MILLIS = 100L;

    private static final long JOIN_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(5);

    private final ExchangeGateway gateway;
    private final EventEngine engine;
    private final Clock clock;
    /** Unbounded on purpose: the frequency rule caps orders at 10/minute, and refusing one because the
     *  queue was full would be a second, invented reason for an order not to exist. */
    private final BlockingQueue<OrderRequestEvent> queue = new LinkedBlockingQueue<>();
    /** Ids already handed over. Confined to the thread that calls {@link #submit} - the engine's. */
    private final Set<String> handedOver = new HashSet<>();
    private final Thread worker;
    private volatile boolean running;
    private volatile boolean closed;

    public OrderSender(ExchangeGateway gateway, EventEngine engine, Clock clock) {
        this.gateway = gateway;
        this.engine = engine;
        this.clock = clock;
        this.worker = new Thread(this::runLoop, "order-sender");
        this.worker.setDaemon(true);
    }

    public void start() {
        if (running) {
            return;
        }
        running = true;
        worker.start();
        log.info("OrderSender started");
    }

    /**
     * Called on the engine thread, by the OMS, after the order's row is written. Offers to the queue
     * and returns; the REST call happens on {@link #worker}.
     */
    @Override
    public boolean submit(OrderRequestEvent request) {
        if (closed) {
            // The worker is gone, so a queued order would never be sent and the row would sit at NEW
            // with nothing to explain it. Refusing out loud is the cheaper failure.
            log.error("Refusing order [{}]: the sender is closed, so nothing would ever send it",
                    request.clientOrderId());
            return false;
        }
        if (!handedOver.add(request.clientOrderId())) {
            // The OMS refuses a redelivered request before it reaches here, so this branch means two
            // components disagree about what has been sent. Say so instead of only declining.
            log.warn("Order [{}] was submitted twice; the second submission was not sent",
                    request.clientOrderId());
            return false;
        }
        queue.offer(request);
        return true;
    }

    @Override
    public void close() {
        closed = true;
        running = false;
        worker.interrupt();
        try {
            worker.join(JOIN_TIMEOUT_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        int unsent = queue.size();
        if (unsent > 0) {
            log.error("OrderSender closed with {} order(s) never sent: {}", unsent,
                    queue.stream().map(OrderRequestEvent::clientOrderId).toList());
        }
        log.info("OrderSender stopped");
    }

    // ------------------------------------------------------------------ the worker

    private void runLoop() {
        while (running) {
            try {
                OrderRequestEvent request = queue.poll(POLL_MILLIS, TimeUnit.MILLISECONDS);
                if (request != null) {
                    engine.publish(outcomeOf(request));
                }
            } catch (InterruptedException e) {
                // close() interrupts to end the park. running decides whether the loop goes round again.
            } catch (Exception e) {
                // Nothing may kill this thread: a dead sender stops every future order, and nothing
                // else in the system would notice until reconciliation found the rows still at NEW.
                log.error("Order sender loop failed", e);
            }
        }
    }

    /**
     * What one attempt at the exchange produces: exactly one event, whether the exchange answered or
     * not. Free of threading so the whole of 边界 6 can be exercised by calling it.
     *
     * <p>Reading the ack is inside the same {@code try} as the call, so a gateway that answers with
     * {@code null} - a bug, but a possible one - becomes the ordinary unknown-outcome alert instead of an
     * NPE that escapes to the loop and leaves the attempt with no event at all.
     */
    Event outcomeOf(OrderRequestEvent request) {
        String clientOrderId = request.clientOrderId();
        try {
            OrderAck ack = gateway.placeOrder(request);
            // Both of these are answers, which is why they become reports rather than alerts: the
            // exchange took the request and said what it did with it.
            return ack.accepted()
                    ? OrderReportEvent.of(clientOrderId, ack.exchangeOrderId(), OrderStatus.SUBMITTED,
                            null, clock.nowMillis())
                    : OrderReportEvent.of(clientOrderId, null, OrderStatus.REJECTED,
                            ack.rejectReason(), clock.nowMillis());
        } catch (ExchangeUnreachableException e) {
            log.warn("Exchange did not answer for [{}]: {}", clientOrderId, e.getMessage());
            return unknownOutcome(RULE_UNREACHABLE, clientOrderId,
                    "the exchange did not answer (" + e.getMessage() + ")");
        } catch (RuntimeException e) {
            log.error("Send path failed for [{}]", clientOrderId, e);
            return unknownOutcome(RULE_SEND_FAILED, clientOrderId,
                    "the send path threw " + e.getClass().getSimpleName());
        }
    }

    /**
     * The one response to an attempt whose outcome is unknown, whatever made it unknown. Stating the
     * conclusion here rather than at each call site is what keeps two causes from drifting into two
     * different responses - which is the whole of 边界 6, and the reason the row is left alone: only
     * FR-EX-04's reconciliation can ask the exchange what it actually holds.
     */
    private RiskAlertEvent unknownOutcome(String ruleId, String clientOrderId, String cause) {
        return RiskAlertEvent.of(ruleId, RiskAlertEvent.Severity.CRITICAL,
                "order " + clientOrderId + ": " + cause + ", so whether this order reached the exchange "
                        + "is unknown - it stays " + OrderStatus.NEW + " and reconciliation decides",
                clock.nowMillis());
    }
}
