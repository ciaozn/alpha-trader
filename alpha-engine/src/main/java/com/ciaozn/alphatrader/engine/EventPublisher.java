package com.ciaozn.alphatrader.engine;

import com.ciaozn.alphatrader.common.event.Event;

/** The only way a handler emits follow-up events. */
@FunctionalInterface
public interface EventPublisher {

    void publish(Event event);
}
