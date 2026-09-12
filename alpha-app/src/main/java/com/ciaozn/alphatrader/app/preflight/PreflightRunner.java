package com.ciaozn.alphatrader.app.preflight;

import com.ciaozn.alphatrader.app.config.AlphaProperties;
import com.ciaozn.alphatrader.app.config.OnlineProperties;
import com.ciaozn.alphatrader.app.alert.AlertProperties;
import com.ciaozn.alphatrader.common.model.Symbol;
import com.ciaozn.alphatrader.common.model.TradingRulesProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Runs {@link PreflightChecks} at startup and prints the result (T502).
 *
 * <p>Order 3: after {@code StartupWiring} has registered handlers and connected the gateway, so the
 * checks describe a process that is actually up - and after {@code AlertWiring}, so an alerting
 * failure is discovered by a check rather than by a warning nobody reads.
 */
@Component
@Profile({"paper", "live"})
@Order(3)
public class PreflightRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PreflightRunner.class);

    private final AlphaProperties properties;
    private final OnlineProperties online;
    private final AlertProperties alert;
    private final ObjectProvider<TradingRulesProvider> rules;
    private final Environment environment;

    public PreflightRunner(AlphaProperties properties, OnlineProperties online, AlertProperties alert,
                           ObjectProvider<TradingRulesProvider> rules, Environment environment) {
        this.properties = properties;
        this.online = online;
        this.alert = alert;
        this.rules = rules;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean liveProfile = environment.matchesProfiles("live");
        // No provider at all means no rules were ever fetched, which is reported as every symbol
        // missing rather than as zero - an empty list would read as "all present".
        TradingRulesProvider provider = rules.getIfAvailable();
        List<Symbol> withoutRules = properties.symbols().stream()
                .map(Symbol::parse)
                .filter(symbol -> provider == null || provider.find(symbol).isEmpty())
                .toList();

        var inputs = new PreflightChecks.Inputs(
                online.gateway(),
                liveProfile,
                properties.binanceTestnet(),
                properties.trading().enabled(),
                credentialsPresent(online.gateway()),
                withoutRules,
                alert.active(),
                present("ALERT_SMTP_AUTH_CODE"));

        String rendered = PreflightChecks.render(PreflightChecks.evaluate(inputs));
        if (rendered.contains("FAILED")) {
            // Loud, but not fatal: the components that own each guarantee still refuse to act.
            log.error("\n{}", rendered);
        } else {
            log.info("\n{}", rendered);
        }
    }

    private static boolean credentialsPresent(OnlineProperties.GatewayType gateway) {
        return switch (gateway) {
            case BINANCE -> present("BINANCE_API_KEY") && present("BINANCE_API_SECRET");
            case OKX -> present("OKX_API_KEY") && present("OKX_API_SECRET") && present("OKX_PASSPHRASE");
        };
    }

    private static boolean present(String name) {
        String value = System.getenv(name);
        return value != null && !value.isBlank();
    }
}
