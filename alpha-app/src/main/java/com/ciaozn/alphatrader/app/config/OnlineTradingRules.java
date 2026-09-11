package com.ciaozn.alphatrader.app.config;

import com.ciaozn.alphatrader.common.model.Symbol;
import com.ciaozn.alphatrader.common.model.TradingRulesProvider;
import com.ciaozn.alphatrader.gateway.binance.BinanceTradingRulesFetcher;
import okhttp3.OkHttpClient;

import java.util.List;

/**
 * Where the online modes get their precision rules (T315, FR-GW-03): one fetch of
 * {@code /fapi/v1/exchangeInfo} at startup, cached for the whole run.
 *
 * <p><b>Why it throws instead of degrading.</b> {@code RiskGate} treats "no rules for this symbol"
 * as a CRITICAL hard stop, and a provider that silently returned defaults would turn that stop into
 * a silently mis-sized order. So a failed fetch fails the application context: a process that
 * cannot align quantities must not sit there waiting for a signal.
 *
 * <p><b>Why the same provider type as backtest.</b> {@link BinanceTradingRulesFetcher#fetch} returns
 * {@link com.ciaozn.alphatrader.common.model.FixedTradingRulesProvider} - the only implementation in
 * the system, and the one the gate's tests already pin. Backtest reads its rules from configuration
 * (DESIGN §10: the replaceable part is the source, not the shape), live reads them from the
 * exchange; both hand the gate the same object, so the gate cannot tell which mode it is in. That
 * is FR-BT-06 in miniature.
 */
final class OnlineTradingRules {

    private OnlineTradingRules() {
    }

    /** Testnet vs live is the only difference between the two REST bases (FR-GW-05). */
    static BinanceTradingRulesFetcher.Settings settings(AlphaProperties properties) {
        return properties.binanceTestnet()
                ? BinanceTradingRulesFetcher.Settings.TESTNET
                : BinanceTradingRulesFetcher.Settings.LIVE;
    }

    /**
     * Fetching happens on the startup thread, never on the event loop: it retries with backoff and
     * therefore sleeps.
     */
    static TradingRulesProvider fetch(AlphaProperties properties) {
        List<Symbol> symbols = properties.symbols().stream().map(Symbol::parse).toList();
        OkHttpClient http = new OkHttpClient();
        BinanceTradingRulesFetcher fetcher = new BinanceTradingRulesFetcher(http, settings(properties));
        return fetcher.fetch(symbols);
    }
}
