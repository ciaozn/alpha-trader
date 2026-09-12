package com.ciaozn.alphatrader.app.status;

import com.ciaozn.alphatrader.engine.EventEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Puts the status observers on the bus and starts the probe (T402), again without editing {@code
 * StartupWiring}.
 *
 * <p><b>{@code @Order(3)}: after {@code StartupWiring} (1) has registered the online handlers and after
 * {@code AlertWiring} (2) has registered the mail handler.</b> These two observers are appended last, and
 * for the heartbeat that position is the whole point - it counts what made it all the way through the
 * pipeline, so a gate that silently stopped seeing events and a loop that stopped seeing events cannot be
 * told apart. Registering them in a constructor instead would put them at the <em>front</em> of the
 * dispatch list, which would make the heartbeat the one component guaranteed to keep reporting healthy
 * while everything behind it is broken.
 *
 * <p>The probe is started here rather than when the bean is created because it talks to the exchange, and
 * {@code StartupWiring} is what connects the gateway: probing earlier would measure a connection that had
 * not been asked to open yet.
 */
@Component
@Profile({"paper", "live"})
@Order(3)
public class StatusWiring implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StatusWiring.class);

    private final EventEngine engine;
    private final EngineHeartbeat heartbeat;
    private final DailyRealizedPnl daily;
    private final GatewayHealthProbe probe;
    private final StatusProperties properties;

    public StatusWiring(EventEngine engine, EngineHeartbeat heartbeat, DailyRealizedPnl daily,
                        GatewayHealthProbe probe, StatusProperties properties) {
        this.engine = engine;
        this.heartbeat = heartbeat;
        this.daily = daily;
        this.probe = probe;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        engine.registerHandler(heartbeat);
        engine.registerHandler(daily);
        probe.start(properties.probePeriod());
        log.info("Status observers registered last in dispatch order; gateway probe every {}",
                properties.probePeriod());
    }
}
