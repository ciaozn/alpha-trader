package com.ciaozn.alphatrader.strategy.config;

import com.ciaozn.alphatrader.strategy.ParamSet;
import com.ciaozn.alphatrader.strategy.Strategy;

/**
 * Discovered through {@code src/test/resources/META-INF/services}, i.e. exactly the way a
 * third-party strategy jar would be: nothing in main/ knows this class exists.
 */
public final class DummyStrategyFactory implements StrategyFactory {

    public static final String TYPE = "dummy";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Strategy create(StrategySpec spec, ParamSet params) {
        return new DummyStrategy(spec.id(), spec.interval(), spec.symbols(),
                params.getInt("window", 20),
                params.getDouble("threshold", 100.0),
                params.getBoolean("loud", false));
    }
}
