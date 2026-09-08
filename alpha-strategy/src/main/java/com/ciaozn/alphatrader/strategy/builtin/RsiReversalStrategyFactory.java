package com.ciaozn.alphatrader.strategy.builtin;

import com.ciaozn.alphatrader.strategy.ParamSet;
import com.ciaozn.alphatrader.strategy.Strategy;
import com.ciaozn.alphatrader.strategy.config.StrategyFactory;
import com.ciaozn.alphatrader.strategy.config.StrategySpec;

/**
 * Builds {@link RsiReversalStrategy} instances from configuration.
 * Accepted parameter keys: {@code period}, {@code oversold}, {@code overbought},
 * {@code atrPeriod}, {@code minAtrPercent} (0 disables the volatility filter).
 */
public final class RsiReversalStrategyFactory implements StrategyFactory {

    @Override
    public String type() {
        return RsiReversalStrategy.TYPE;
    }

    @Override
    public Strategy create(StrategySpec spec, ParamSet params) {
        RsiReversalStrategy.Params defaults = RsiReversalStrategy.Params.DEFAULT;
        RsiReversalStrategy.Params configured = new RsiReversalStrategy.Params(
                params.getInt("period", defaults.period()),
                params.getDouble("oversold", defaults.oversold()),
                params.getDouble("overbought", defaults.overbought()),
                params.getInt("atrPeriod", defaults.atrPeriod()),
                params.getDouble("minAtrPercent", defaults.minAtrPercent()));
        return new RsiReversalStrategy(spec.id(), spec.interval(), spec.symbols(), configured);
    }
}
