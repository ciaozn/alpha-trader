package com.ciaozn.alphatrader.execution;

import com.ciaozn.alphatrader.common.event.OrderRequestEvent;

/**
 * Where the OMS hands an order that survived the risk gate to whatever talks to the exchange.
 *
 * <p>A seam rather than a call to {@code ExchangeGateway} because the two belong on different threads
 * and must stay there: {@code EventHandler}'s contract forbids network IO on the engine thread, and
 * {@code placeOrder} is a blocking REST call. The hand-over is a queue offer, made on the engine thread
 * after the order's row is written, so a crash between the two leaves an unsent row rather than a sent
 * order nobody recorded.
 *
 * <p>It is also what a replay driver substitutes. Recovery replays the journal, and the journal already
 * contains the exchange's answer to every order it contains the request for - so a replay wired to a
 * sending outbox would place live orders a second time. Replaying through an outbox that does not send
 * rebuilds the same state without touching the exchange, and needs no change to the OMS.
 */
@FunctionalInterface
public interface OrderOutbox {

    /**
     * Hands one order over.
     *
     * @return {@code false} if this {@code clientOrderId} had already been handed over, so nothing was
     *         queued. That is not a failure - it is FR-EX-02 holding - and the caller must not respond
     *         to it by sending the order some other way.
     */
    boolean submit(OrderRequestEvent request);
}
