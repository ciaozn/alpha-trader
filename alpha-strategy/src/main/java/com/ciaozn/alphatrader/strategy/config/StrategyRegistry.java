package com.ciaozn.alphatrader.strategy.config;

import com.ciaozn.alphatrader.strategy.ParamSet;
import com.ciaozn.alphatrader.strategy.Strategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;

/**
 * Turns configuration into running strategies: resolves each spec's {@code type} to a
 * {@link StrategyFactory} and builds the enabled ones.
 *
 * <p>Output order is the configuration order, which is the dispatch order inside
 * {@code StrategyEngine} and therefore part of what makes a run reproducible (NFR-04).
 * Factory discovery order is deliberately irrelevant: it is only used to build a type lookup.
 *
 * <p>Everything wrong with a strategy configuration is a startup failure, never a warning -
 * an unknown type, a duplicate id, a bad parameter value or a misspelled parameter key all
 * mean the system would trade something other than what was configured.
 */
public final class StrategyRegistry {

    private static final Logger log = LoggerFactory.getLogger(StrategyRegistry.class);

    private final Map<String, StrategyFactory> factories;

    /** Discovers the factories on the classpath via {@code META-INF/services}. */
    public StrategyRegistry() {
        this(ServiceLoader.load(StrategyFactory.class));
    }

    public StrategyRegistry(Iterable<StrategyFactory> discovered) {
        Map<String, StrategyFactory> byType = new LinkedHashMap<>();
        for (StrategyFactory factory : discovered) {
            StrategyFactory previous = byType.put(factory.type(), factory);
            if (previous != null) {
                throw new IllegalArgumentException("Two strategy factories claim type '" + factory.type() + "': "
                        + previous.getClass().getName() + " and " + factory.getClass().getName());
            }
        }
        this.factories = Collections.unmodifiableMap(byType);
        log.info("Strategy factories discovered: {}", byType.keySet());
    }

    public Set<String> knownTypes() {
        return factories.keySet();
    }

    /** Builds the enabled strategies, in spec order. */
    public List<Strategy> build(List<StrategySpec> specs) {
        List<Strategy> strategies = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        for (StrategySpec spec : specs) {
            if (!spec.enabled()) {
                log.info("Strategy {} (type={}) disabled by configuration", spec.id(), spec.type());
                continue;
            }
            if (!ids.add(spec.id())) {
                throw new IllegalArgumentException("Duplicate strategy id '" + spec.id() + "' in configuration");
            }
            strategies.add(create(spec));
        }
        if (strategies.isEmpty()) {
            log.warn("No strategy enabled - the system will consume market data and do nothing with it");
        }
        return strategies;
    }

    private Strategy create(StrategySpec spec) {
        StrategyFactory factory = factories.get(spec.type());
        if (factory == null) {
            throw new IllegalArgumentException("Unknown strategy type '" + spec.type() + "' for id '" + spec.id()
                    + "'; known types: " + knownTypes());
        }
        ParamSet params = new ParamSet(spec.id(), spec.params());
        Strategy strategy = factory.create(spec, params);
        params.rejectUnknownKeys();
        if (!strategy.id().equals(spec.id())) {
            throw new IllegalArgumentException("Factory for type '" + spec.type() + "' ignored the configured id: "
                    + "built '" + strategy.id() + "', configured '" + spec.id() + "'");
        }
        log.info("Strategy enabled: id={} type={} symbols={} interval={} params={}",
                spec.id(), spec.type(), spec.symbols(), spec.interval(), spec.params());
        return strategy;
    }
}
