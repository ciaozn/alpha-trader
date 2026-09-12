package com.ciaozn.alphatrader.app.status;

import com.ciaozn.alphatrader.app.config.AlphaProperties;
import com.ciaozn.alphatrader.common.portfolio.Portfolio;
import com.ciaozn.alphatrader.common.time.Clock;
import com.ciaozn.alphatrader.engine.EventEngine;
import com.ciaozn.alphatrader.gateway.ExchangeGateway;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * The read-only side of the online modes (T402, FR-OP-02).
 *
 * <p><b>Every bean here injects something that already existed</b> - {@code Portfolio}, {@code Clock},
 * {@code EventEngine}, {@code ExchangeGateway}, {@code AlphaProperties} - rather than keeping its own copy
 * of the state the status page reports. Keeping a second account book, or a second idea of "which mode am
 * I", would guarantee that the endpoint and the trading path eventually disagree, and the disagreement
 * would always be discovered while something else was on fire.
 *
 * <p><b>Online profiles only</b>, for the same reason {@code OnlineWiringConfig} states at length: backtest
 * and download must be able to run and exit in a container with no gateway, no running engine and nothing
 * to report, and one unannotated bean would give them a half-built status page to maintain.
 */
@Configuration
@Profile({"paper", "live"})
public class StatusConfig {

    /** The loop's pulse. A bean because {@code StatusWiring} registers it and the factory reads it. */
    @Bean
    EngineHeartbeat engineHeartbeat(Clock clock) {
        return new EngineHeartbeat(clock);
    }

    /**
     * Today's realized P&L. Constructed eagerly even though nothing calls it during startup: its baseline
     * is whatever the book had realized before registration, so creating it late would silently claim those
     * fills for today.
     */
    @Bean
    DailyRealizedPnl dailyRealizedPnl(Portfolio portfolio, Clock clock) {
        return new DailyRealizedPnl(portfolio, clock);
    }

    @Bean(destroyMethod = "close")
    GatewayHealthProbe gatewayHealthProbe(ExchangeGateway gateway, Clock clock) {
        return new GatewayHealthProbe(gateway, clock);
    }

    @Bean
    StatusSnapshotFactory statusSnapshotFactory(EventEngine engine, Portfolio portfolio,
                                                EngineHeartbeat heartbeat, DailyRealizedPnl daily,
                                                GatewayHealthProbe probe, Clock clock,
                                                AlphaProperties properties,
                                                StatusProperties status) {
        return new StatusSnapshotFactory(engine, portfolio, heartbeat, daily, probe, clock,
                mode(properties), status.snapshotTimeout());
    }

    /**
     * The mode this process believes it is in. Read from {@code alpha.mode} rather than from the active
     * Spring profile: they are set from the same source, but the property is what everything else in the
     * system reads to decide what it is doing, and an endpoint that answered from the other one would be
     * reporting its own configuration instead of the trading path's.
     */
    private static String mode(AlphaProperties properties) {
        return properties.mode() == null || properties.mode().isBlank()
                ? "unknown"
                : properties.mode();
    }
}
