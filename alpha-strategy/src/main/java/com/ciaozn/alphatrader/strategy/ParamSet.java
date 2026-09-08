package com.ciaozn.alphatrader.strategy;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Typed view over one strategy's configuration parameters, with typo protection.
 *
 * <p>Keys are matched case-insensitively and ignore {@code -} / {@code _}, so {@code fast-period},
 * {@code fastPeriod} and {@code FAST_PERIOD} are the same key: configuration style must not change
 * behaviour, and Spring's relaxed binding cannot silently rename a key out from under a factory.
 *
 * <p>Every key that is read is remembered. {@link #rejectUnknownKeys()} then fails startup on
 * whatever is left over, because a misspelled parameter that quietly falls back to its default is
 * exactly the kind of mistake that costs money.
 */
public final class ParamSet {

    private final String strategyId;
    private final Map<String, String> values = new LinkedHashMap<>();
    private final Map<String, String> writtenKeys = new LinkedHashMap<>();
    private final Set<String> read = new LinkedHashSet<>();

    public ParamSet(String strategyId, Map<String, String> raw) {
        this.strategyId = strategyId;
        if (raw != null) {
            raw.forEach((key, value) -> {
                String normalized = normalize(key);
                values.put(normalized, value);
                writtenKeys.put(normalized, key);
            });
        }
    }

    public int getInt(String key, int defaultValue) {
        String raw = lookup(key);
        if (raw == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw invalid(key, raw, "an integer");
        }
    }

    public double getDouble(String key, double defaultValue) {
        String raw = lookup(key);
        if (raw == null) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            throw invalid(key, raw, "a number");
        }
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        String raw = lookup(key);
        if (raw == null) {
            return defaultValue;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if (normalized.equals("true") || normalized.equals("false")) {
            return Boolean.parseBoolean(normalized);
        }
        throw invalid(key, raw, "true or false");
    }

    /** Fails startup on any configured key no factory asked for. */
    public void rejectUnknownKeys() {
        Set<String> unknown = new LinkedHashSet<>(values.keySet());
        unknown.removeAll(read);
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("Strategy " + strategyId + " has unknown parameter(s) "
                    + unknown.stream().map(writtenKeys::get).toList()
                    + "; configured keys are matched ignoring case, '-' and '_'");
        }
    }

    private String lookup(String key) {
        String normalized = normalize(key);
        String value = values.get(normalized);
        if (value != null) {
            read.add(normalized);
        }
        return value;
    }

    private IllegalArgumentException invalid(String key, String raw, String expected) {
        return new IllegalArgumentException(
                "Strategy " + strategyId + " parameter '" + key + "' = '" + raw + "' is not " + expected);
    }

    private static String normalize(String key) {
        StringBuilder normalized = new StringBuilder(key.length());
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c != '-' && c != '_') {
                normalized.append(Character.toLowerCase(c));
            }
        }
        return normalized.toString();
    }
}
