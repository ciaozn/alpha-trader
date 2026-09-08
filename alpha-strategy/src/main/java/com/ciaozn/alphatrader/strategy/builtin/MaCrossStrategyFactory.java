package com.ciaozn.alphatrader.strategy.builtin;

import com.ciaozn.alphatrader.strategy.ParamSet;
import com.ciaozn.alphatrader.strategy.Strategy;
import com.ciaozn.alphatrader.strategy.config.StrategyFactory;
import com.ciaozn.alphatrader.strategy.config.StrategySpec;

/**
 * Builds {@link MaCrossStrategy} instances from configuration.
 * Accepted parameter keys: {@code fastPeriod}, {@code slowPeriod}, {@code allowShort}.
 */
public final class MaCrossStrategyFactory implements StrategyFactory {

    @Override
    public String type() {
        return MaCrossStrategy.TYPE;
    }

    @Override
    public Strategy create(StrategySpec spec, ParamSet params) {
        MaCrossStrategy.Params defaults = MaCrossStrategy.Params.DEFAULT;
        MaCrossStrategy.Params configured = new MaCrossStrategy.Params(
                params.getInt("fastPeriod", defaults.fastPeriod()),
                params.getInt("slowPeriod", defaults.slowPeriod()),
                params.getBoolean("allowShort", defaults.allowShort()));
        return new MaCrossStrategy(spec.id(), spec.interval(), spec.symbols(), configured);
    }
}
