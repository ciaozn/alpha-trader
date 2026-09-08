package com.ciaozn.alphatrader.strategy.config;

import com.ciaozn.alphatrader.common.event.KlineEvent;
import com.ciaozn.alphatrader.common.model.Direction;
import com.ciaozn.alphatrader.common.model.Interval;
import com.ciaozn.alphatrader.common.model.Symbol;
import com.ciaozn.alphatrader.strategy.Strategy;
import com.ciaozn.alphatrader.strategy.StrategyContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A strategy that exists only to prove spec scenario 6: it is not referenced by any production
 * code, yet enabling it in configuration puts it in the live dispatch path.
 */
public final class DummyStrategy implements Strategy {

    private final String id;
    private final Interval interval;
    private final Set<Symbol> symbols;
    private final int window;
    private final double threshold;
    private final boolean loud;
    private final List<KlineEvent> seen = new ArrayList<>();

    public DummyStrategy(String id, Interval interval, Set<Symbol> symbols,
                         int window, double threshold, boolean loud) {
        this.id = id;
        this.interval = interval;
        this.symbols = Collections.unmodifiableSet(new LinkedHashSet<>(symbols));
        this.window = window;
        this.threshold = threshold;
        this.loud = loud;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public Set<Symbol> symbols() {
        return symbols;
    }

    @Override
    public Interval interval() {
        return interval;
    }

    @Override
    public void onKline(KlineEvent event, StrategyContext context) {
        seen.add(event);
        if (event.kline().close().doubleValue() > threshold) {
            context.emit(event.symbol(), Direction.LONG, 1.0, "dummy above " + threshold);
        }
    }

    public List<KlineEvent> seen() {
        return Collections.unmodifiableList(seen);
    }

    public int window() {
        return window;
    }

    public double threshold() {
        return threshold;
    }

    public boolean loud() {
        return loud;
    }
}
