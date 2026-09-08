package com.ciaozn.alphatrader.strategy.config;

import com.ciaozn.alphatrader.strategy.ParamSet;
import com.ciaozn.alphatrader.strategy.Strategy;

/**
 * Creates strategies of one configuration {@code type} (FR-ST-01, spec scenario 6).
 *
 * <p>Implementations are discovered through {@code META-INF/services}, so shipping a new
 * strategy requires no change to the event engine, this registry, the risk pipeline or the
 * OMS - the developer writes a {@link Strategy}, a factory, and enables it in the yml.
 */
public interface StrategyFactory {

    /** Value of {@code alpha.strategies[].type} this factory answers to; must be unique. */
    String type();

    /**
     * @param params typed view over {@code spec.params()}. Read every key the strategy accepts:
     *               the registry fails startup on whatever is left unread, so a misspelled
     *               parameter cannot silently fall back to a default.
     */
    Strategy create(StrategySpec spec, ParamSet params);
}
