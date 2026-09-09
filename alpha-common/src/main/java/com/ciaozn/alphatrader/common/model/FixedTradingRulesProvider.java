package com.ciaozn.alphatrader.common.model;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Rules that are known up front: backtest configuration, a test fixture, or a snapshot of
 * exchangeInfo taken offline. Immutable, so it can be shared between threads.
 */
public final class FixedTradingRulesProvider implements TradingRulesProvider {

    private final Map<Symbol, TradingRules> rules;

    public FixedTradingRulesProvider(Collection<TradingRules> rules) {
        Map<Symbol, TradingRules> bySymbol = new LinkedHashMap<>();
        for (TradingRules entry : rules) {
            TradingRules previous = bySymbol.put(entry.symbol(), entry);
            if (previous != null) {
                throw new IllegalArgumentException("Duplicate trading rules for " + entry.symbol().unified());
            }
        }
        this.rules = Collections.unmodifiableMap(bySymbol);
    }

    public static FixedTradingRulesProvider of(TradingRules... rules) {
        return new FixedTradingRulesProvider(List.of(rules));
    }

    @Override
    public Optional<TradingRules> find(Symbol symbol) {
        return Optional.ofNullable(rules.get(symbol));
    }

    public Set<Symbol> symbols() {
        return rules.keySet();
    }
}
