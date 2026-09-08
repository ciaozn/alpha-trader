package com.ciaozn.alphatrader.app.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Central typed config (alpha.*). Defaults are safe: testnet on, trading off. */
@ConfigurationProperties(prefix = "alpha")
public record AlphaProperties(
        String mode,
        List<String> symbols,
        String interval,
        boolean binanceTestnet,
        Trading trading,
        Path journalDir,
        BigDecimal initialCash,
        List<StrategyEntry> strategies) {

    public record Trading(boolean enabled) {
    }

    /**
     * One {@code alpha.strategies[]} entry (FR-ST-01). {@code symbols} and {@code interval}
     * fall back to the global ones; {@code enabled} defaults to true - listing a strategy
     * means wanting to run it.
     */
    public record StrategyEntry(
            String id,
            String type,
            Boolean enabled,
            List<String> symbols,
            String interval,
            Map<String, String> params) {
    }

    public AlphaProperties {
        if (symbols == null || symbols.isEmpty()) {
            symbols = List.of("BTCUSDT.PERP");
        }
        if (interval == null || interval.isBlank()) {
            interval = "1h";
        }
        if (journalDir == null) {
            journalDir = Path.of("logs");
        }
        if (trading == null) {
            trading = new Trading(false);
        }
        if (initialCash == null) {
            initialCash = new BigDecimal("10000");
        }
        if (initialCash.signum() <= 0) {
            throw new IllegalArgumentException("alpha.initial-cash must be positive, got " + initialCash);
        }
        if (strategies == null) {
            strategies = List.of();
        }
    }
}
