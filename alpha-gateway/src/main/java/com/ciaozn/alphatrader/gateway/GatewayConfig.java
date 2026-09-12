package com.ciaozn.alphatrader.gateway;

/**
 * Gateway bootstrap config. apiKey/apiSecret may be null for market-data-only usage
 * (public streams need no auth) - trading methods must fail fast if credentials are absent.
 */
public record GatewayConfig(
        boolean testnet,
        String apiKey,
        String apiSecret,
        String passphrase) {

    /**
     * Binance has no passphrase; OKX requires one. Kept as an overload rather than a nullable field
     * every caller has to remember, so the difference stays visible in the type.
     */
    public GatewayConfig(boolean testnet, String apiKey, String apiSecret) {
        this(testnet, apiKey, apiSecret, null);
    }

    public static GatewayConfig marketDataOnly(boolean testnet) {
        return new GatewayConfig(testnet, null, null, null);
    }

    /** Credentials plus OKX's passphrase. */
    public static GatewayConfig withPassphrase(boolean testnet, String apiKey, String apiSecret,
                                               String passphrase) {
        return new GatewayConfig(testnet, apiKey, apiSecret, passphrase);
    }

    public boolean hasCredentials() {
        return apiKey != null && !apiKey.isBlank() && apiSecret != null && !apiSecret.isBlank();
    }
}
