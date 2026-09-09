package com.ciaozn.alphatrader.app.config;

import com.ciaozn.alphatrader.common.portfolio.Portfolio;
import com.ciaozn.alphatrader.common.time.Clock;
import com.ciaozn.alphatrader.strategy.StrategyEngine;
import com.ciaozn.alphatrader.strategy.config.StrategyRegistry;
import com.ciaozn.alphatrader.strategy.config.StrategySpec;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.util.LinkedHashSet;
import java.util.List;

/**
 * Strategy assembly. The only thing this class knows about strategies is how to read their
 * configuration: factories are discovered on the classpath, so a new strategy needs a
 * {@code Strategy}, a {@code StrategyFactory} and a yml entry - nothing here, nothing in the
 * engine, risk pipeline or OMS ever changes (FR-ST-01, NFR-06, spec scenario 6).
 *
 * <p>{@link #specs} is public because the backtest wiring reads the same {@code alpha.strategies}
 * block. One mapping, two consumers: a backtest configured from a different translation of the same
 * yml would not be running the strategies live runs (FR-BT-06).
 */
@Configuration
public class StrategyConfig {

    @Bean
    StrategyRegistry strategyRegistry() {
        return new StrategyRegistry();
    }

    /**
     * Online modes only. Backtest builds its own {@code StrategyEngine} per run inside
     * {@code BacktestRunner}, on that run's {@code Portfolio} and {@code VirtualClock}, and rebuilds
     * the strategies every time: they carry state across bars, so a singleton warmed by one replay
     * would silently continue into the next.
     */
    @Bean
    @Profile({"paper", "live"})
    StrategyEngine strategyEngine(StrategyRegistry registry, AlphaProperties properties,
                                 Portfolio portfolio, Clock clock) {
        return new StrategyEngine(registry.build(specs(properties)), portfolio, clock);
    }

    public static List<StrategySpec> specs(AlphaProperties properties) {
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
