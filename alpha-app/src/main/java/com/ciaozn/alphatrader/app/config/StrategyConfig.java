package com.ciaozn.alphatrader.app.config;

import com.ciaozn.alphatrader.common.portfolio.Portfolio;
import com.ciaozn.alphatrader.common.time.Clock;
import com.ciaozn.alphatrader.strategy.StrategyEngine;
import com.ciaozn.alphatrader.strategy.config.StrategyRegistry;
import com.ciaozn.alphatrader.strategy.config.StrategySpec;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashSet;
import java.util.List;

/**
 * Strategy assembly. The only thing this class knows about strategies is how to read their
 * configuration: factories are discovered on the classpath, so a new strategy needs a
 * {@code Strategy}, a {@code StrategyFactory} and a yml entry - nothing here, nothing in the
 * engine, risk pipeline or OMS ever changes (FR-ST-01, NFR-06, spec scenario 6).
 */
@Configuration
public class StrategyConfig {

    @Bean
    StrategyRegistry strategyRegistry() {
        return new StrategyRegistry();
    }

    @Bean
    StrategyEngine strategyEngine(StrategyRegistry registry, AlphaProperties properties,
                                 Portfolio portfolio, Clock clock) {
        return new StrategyEngine(registry.build(specs(properties)), portfolio, clock);
    }

    private static List<StrategySpec> specs(AlphaProperties properties) {
        return properties.strategies().stream()
                .map(entry -> StrategySpec.of(
                        entry.id(),
                        entry.type(),
                        entry.enabled() == null || entry.enabled(),
                        new LinkedHashSet<>(orGlobal(entry.symbols(), properties.symbols())),
                        orGlobal(entry.interval(), properties.interval()),
                        entry.params()))
                .toList();
    }

    private static List<String> orGlobal(List<String> configured, List<String> global) {
        return configured == null || configured.isEmpty() ? global : configured;
    }

    private static String orGlobal(String configured, String global) {
        return configured == null || configured.isBlank() ? global : configured;
    }
}
