package com.ciaozn.alphatrader.engine;

import com.ciaozn.alphatrader.common.event.Event;

/**
 * A component attached to the event loop (strategy, risk, OMS, echo...).
 * Handlers run ON the single engine thread, in registration order - never block,
 * never do network IO here (gateways run on their own threads and publish in).
 *
 * <p>Events emitted via {@link EventPublisher} during dispatch join the cascade queue
 * and are fully processed within the same round, before any external event (FR-EN-01).
 */
@FunctionalInterface
public interface EventHandler {

    void onEvent(Event event, EventPublisher publisher);
}
