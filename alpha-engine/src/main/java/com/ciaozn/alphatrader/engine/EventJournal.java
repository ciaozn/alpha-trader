package com.ciaozn.alphatrader.engine;

import com.ciaozn.alphatrader.common.event.Event;

import java.util.List;

/**
 * Append-only durable log of every event, written BEFORE dispatch (FR-EN-02).
 * Basis for crash recovery, replay and bit-exact backtest reproduction (NFR-05).
 */
public interface EventJournal extends AutoCloseable {

    void append(Event event);

    List<Event> readAll();

    @Override
    void close();

    /** Journal that discards everything - for unit tests that don't care about durability. */
    static EventJournal noop() {
        return new EventJournal() {
            @Override
            public void append(Event event) {
            }

            @Override
            public List<Event> readAll() {
                return List.of();
            }

            @Override
            public void close() {
            }
        };
    }
}
