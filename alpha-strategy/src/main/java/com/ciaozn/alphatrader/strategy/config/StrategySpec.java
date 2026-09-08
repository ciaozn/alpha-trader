package com.ciaozn.alphatrader.strategy.config;

import com.ciaozn.alphatrader.common.model.Interval;
import com.ciaozn.alphatrader.common.model.Symbol;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * One strategy instance as declared in {@code application.yml} (FR-ST-01).
 *
 * <p>The {@code id} is what the rest of the system knows the strategy by: it stamps every
 * signal, prefixes every clientOrderId (FR-EX-02) and keys the per-strategy performance
 * breakdown. The same {@code type} may be enabled several times under different ids -
 * BTC 1h MA-cross and ETH 4h MA-cross are two independent instances with their own state.
 *
 * <p>Parameters stay strings here: each factory owns the meaning of its own keys, so adding a
 * strategy never touches this record.
 */
public record StrategySpec(
        String id,
        String type,
        boolean enabled,
        Set<Symbol> symbols,
        Interval interval,
        Map<String, String> params) {

    public StrategySpec {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Strategy id must not be blank");
        }
        // The id becomes the clientOrderId prefix, so the exchange charset applies (FR-EX-02).
        if (!id.matches("[A-Za-z0-9_.-]{1,20}")) {
            throw new IllegalArgumentException("Strategy id '" + id + "' must be 1-20 characters "
                    + "from letters, digits, '.', '-' and '_'");
        }
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("Strategy " + id + " has no type");
        }
        if (symbols == null || symbols.isEmpty()) {
            throw new IllegalArgumentException("Strategy " + id + " has no symbols");
        }
        if (interval == null) {
            throw new IllegalArgumentException("Strategy " + id + " has no interval");
        }
        // Configuration order, not Set.copyOf: its iteration order may vary between JVM runs.
        symbols = Collections.unmodifiableSet(new LinkedHashSet<>(symbols));
        params = params == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(params));
    }

    /** Builds a spec from the raw configuration form (symbol/interval strings). */
    public static StrategySpec of(String id, String type, boolean enabled,
                                  Set<String> symbols, String interval, Map<String, String> params) {
        Set<Symbol> parsed = new LinkedHashSet<>();
        if (symbols != null) {
            symbols.forEach(symbol -> parsed.add(Symbol.parse(symbol)));
        }
        return new StrategySpec(id, type, enabled, parsed,
                interval == null || interval.isBlank() ? null : Interval.fromBinanceCode(interval),
                params);
    }
}
