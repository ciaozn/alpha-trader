package com.ciaozn.alphatrader.app.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Fail-fast secret check (T008 / FR-SEC-01): when trading is enabled in paper/live,
 * the required API credentials MUST be present as environment variables.
 * Keys never live in files that enter git - .env is gitignored, .env.example documents names only.
 */
@Component
@Order(0)
public class EnvValidator implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(EnvValidator.class);

    private final Environment env;
    private final AlphaProperties properties;

    public EnvValidator(Environment env, AlphaProperties properties) {
        this.env = env;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean guardedProfile = env.matchesProfiles("paper") || env.matchesProfiles("live");
        if (!guardedProfile || !properties.trading().enabled()) {
            log.info("EnvValidator: market-data-only mode, no credentials required");
            return;
        }
        requireEnv("BINANCE_API_KEY");
        requireEnv("BINANCE_API_SECRET");
        log.info("EnvValidator: trading credentials present (values never logged)");
    }

    private void requireEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "Missing required environment variable " + name
                            + " (trading.enabled=true). Set it in .env / shell - never in committed files.");
        }
    }
}
