package com.ciaozn.alphatrader.common.model;

/**
 * Unified internal symbol representation, e.g. {@code BTCUSDT.PERP} (FR-GW-04).
 * Exchange-specific naming is produced by the mapping helpers here; components
 * outside the gateway layer must only use this unified form.
 */
public record Symbol(String base, String quote, Kind kind) {

    public enum Kind {
        PERP
    }

    public static Symbol perp(String base, String quote) {
        return new Symbol(base.toUpperCase(), quote.toUpperCase(), Kind.PERP);
    }

    /** Parse unified form "BTCUSDT.PERP" (quote USDT/USDⓈ implied by suffix pattern). */
    public static Symbol parse(String unified) {
        String[] parts = unified.split("\\.");
        if (parts.length != 2 || !parts[1].equalsIgnoreCase("PERP")) {
            throw new IllegalArgumentException("Not a unified perpetual symbol: " + unified);
        }
        String pair = parts[0].toUpperCase();
        for (String quote : new String[]{"USDT", "USDC", "USD"}) {
            if (pair.endsWith(quote)) {
                return perp(pair.substring(0, pair.length() - quote.length()), quote);
            }
        }
        throw new IllegalArgumentException("Unknown quote asset in symbol: " + unified);
    }

    /** Unified internal form, e.g. BTCUSDT.PERP. */
    public String unified() {
        return base + quote + "." + kind;
    }

    /** Binance USDⓈ-M form: BTCUSDT. */
    public String binance() {
        return base + quote;
    }

    /** OKX SWAP form: BTC-USDT-SWAP. */
    public String okx() {
        return base + "-" + quote + "-SWAP";
    }

    @Override
    public String toString() {
        return unified();
    }
}
