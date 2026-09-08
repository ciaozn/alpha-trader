package com.ciaozn.alphatrader.app.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.util.List;

/** Central typed config (alpha.*). Defaults are safe: testnet on, trading off. */
@ConfigurationProperties(prefix = "alpha")
public record AlphaProperties(
        String mode,
        List<String> symbols,
        String interval,
        boolean binanceTestnet,
        Trading trading,
        Path journalDir) {

    public record Trading(boolean enabled) {
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
    }
}
