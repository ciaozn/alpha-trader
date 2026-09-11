package com.ciaozn.alphatrader.app.alert;

import com.ciaozn.alphatrader.engine.EventEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Registers the alert handler without anyone having to edit {@code StartupWiring} (T401).
 *
 * <p><b>Why {@code @Order(2)} and not an unconditional call.</b> {@code StartupWiring} is the online
 * assembly's own boot sequence - Order(1): register {@code OnlineHandlers}, start the engine, connect
 * the gateway. Appending after it keeps registration order identical to dispatch order with the one
 * guarantee that matters here intact: everything the operator cares about has already seen the alert by
 * the time this handler does. Registering from this class rather than by adding a line to that file is
 * what keeps two parallel pieces of work from touching the same line.
 *
 * <p><b>Why after the engine has started.</b> Handlers can be registered at any time - the list is
 * copy-on-write - but before startup there is no loop to run them on. Registering here rather than in a
 * constructor is also what avoids a constructor that reaches into a half-built context; the one price
 * is a window, between {@code StartupWiring}'s {@code gateway.connect} and this runner, in which an
 * alert would go unobserved. Nothing publishes RiskAlertEvents in that window: they come from the risk
 * pipeline and reconciliation, both of which need market data or orders first.
 */
@Component
@Profile({"paper", "live"})
@Order(2)
public class AlertWiring implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AlertWiring.class);

    private final EventEngine engine;
    private final EmailAlertHandler handler;
    private final AlertProperties properties;

    public AlertWiring(EventEngine engine, EmailAlertHandler handler, AlertProperties properties) {
        this.engine = engine;
        this.handler = handler;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        engine.registerHandler(handler);
        handler.logArmed(properties);
        log.info("Alert handler registered last in dispatch order");
    }
}
