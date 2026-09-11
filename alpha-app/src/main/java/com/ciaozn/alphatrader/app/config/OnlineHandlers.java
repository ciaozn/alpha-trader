package com.ciaozn.alphatrader.app.config;

import com.ciaozn.alphatrader.engine.EventHandler;

import java.util.List;

/**
 * The handler sequence for the online modes, in dispatch order (T319).
 *
 * <p>A record rather than a bare {@code List<EventHandler>} because the names are load-bearing: the
 * constraint "the signal recorder runs before the risk gate" is only enforceable by a test if the
 * sequence can be read. Asserting on class names recovered from {@code handler.getClass()} would
 * work, but it would also let someone rename a bean and silently reorder dispatch.
 *
 * @param names    parallel to {@code handlers}; what a test asserts on
 * @param handlers registration order, which the engine uses as dispatch order
 */
public record OnlineHandlers(List<String> names, List<EventHandler> handlers) {

    public OnlineHandlers {
        if (names.size() != handlers.size()) {
            throw new IllegalArgumentException("names and handlers must be parallel lists");
        }
    }

    public int indexOf(String name) {
        return names.indexOf(name);
    }
}
