package com.ciaozn.alphatrader.gateway;

/**
 * Gateway bootstrap config. apiKey/apiSecret may be null for market-data-only usage
 * (public streams need no auth) - trading methods must fail fast if credentials are absent.
 */
public record GatewayConfig(
        boolean testnet,
        String apiKey,
        String apiSecret) {

    public static GatewayConfig marketDataOnly(boolean testnet) {
        return new GatewayConfig(testnet, null, null);
    }

    public boolean hasCredentials() {
        return apiKey != null && !apiKey.isBlank() && apiSecret != null && !apiSecret.isBlank();
    }
}
