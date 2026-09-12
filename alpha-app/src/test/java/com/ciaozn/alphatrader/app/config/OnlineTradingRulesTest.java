package com.ciaozn.alphatrader.app.config;

import com.ciaozn.alphatrader.gateway.binance.BinanceTradingRulesFetcher;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OnlineTradingRulesTest {

    private static AlphaProperties properties(boolean testnet, List<String> symbols) {
        return new AlphaProperties("paper", symbols, "1h", testnet, null, null, null, null, null,
                null, null, null);
    }

    @Test
    void testnetFlagSelectsTheRestBase() {
        assertThat(OnlineTradingRules.settings(properties(true, List.of("BTCUSDT.PERP"))))
                .isSameAs(BinanceTradingRulesFetcher.Settings.TESTNET);
        assertThat(OnlineTradingRules.settings(properties(false, List.of("BTCUSDT.PERP"))))
                .isSameAs(BinanceTradingRulesFetcher.Settings.LIVE);
    }

    @Test
    void theTwoBasesAreDifferentHosts() {
        // If these ever collapse into one host, "testnet" has stopped meaning anything and
        // a wiring mistake would place real orders from a test run.
        assertThat(BinanceTradingRulesFetcher.Settings.TESTNET.baseUrl())
                .isEqualTo("https://testnet.binancefuture.com");
        assertThat(BinanceTradingRulesFetcher.Settings.LIVE.baseUrl())
                .isEqualTo("https://fapi.binance.com");
    }
}
