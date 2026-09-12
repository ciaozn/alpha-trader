package com.ciaozn.alphatrader.app.risk;

import com.ciaozn.alphatrader.common.portfolio.Portfolio;
import com.ciaozn.alphatrader.common.time.Clock;
import com.ciaozn.alphatrader.engine.EventEngine;
import com.ciaozn.alphatrader.risk.RiskGate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Wires the risk-reload service into the online modes (T404).
 *
 * <p><b>Online profiles only</b>, for the reason {@code OnlineWiringConfig} states at length: a
 * backtest or download run has no running engine and no operator, and an unannotated bean would give
 * those profiles a reload path that could only ever refuse. It also means the reload endpoint simply
 * does not exist in a mode that must not be driven by REST.
 *
 * <p>The service builds its pipeline from the <em>posted</em> block rather than from
 * {@code AlphaProperties}, so a reload does not need the configuration file to have changed - the
 * point of FR-RK-09 is to change limits without editing and restarting the process.
 */
@Configuration
@Profile({"paper", "live"})
public class RiskReloadConfig {

    @Bean
    RiskReloadService riskReloadService(RiskGate gate, Portfolio portfolio, EventEngine engine, Clock clock) {
        return new RiskReloadService(gate, portfolio, engine, clock);
    }
}
