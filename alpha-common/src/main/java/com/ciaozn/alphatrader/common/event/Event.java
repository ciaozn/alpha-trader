package com.ciaozn.alphatrader.common.event;

import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Root of the event hierarchy. Everything that flows through the system is an Event.
 *
 * <p>Sealed so the compiler enforces exhaustive handling (FR-EN-01).
 * Two timestamps matter: {@link #eventId()} is a global monotonic sequence assigned at
 * creation; {@link #timestamp()} is the BUSINESS time (historical time during backtest,
 * wall clock during live). Components must never read the system clock directly (FR-EN-03).
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
public sealed interface Event
        permits KlineEvent, TickerEvent, SignalEvent, OrderRequestEvent,
                OrderUpdateEvent, FillEvent, TimerEvent, RiskAlertEvent {

    /** Global monotonic sequence number, assigned by {@link EventIds}. */
    long eventId();

    /** Business timestamp in epoch millis (VirtualClock time in backtest). */
    long timestamp();
}
