package com.ciaozn.alphatrader.common.model;

import java.util.Optional;

/**
 * Where order-time precision constraints come from (FR-GW-03).
 *
 * <p>Live and paper answer from the exchangeInfo the gateway fetched and cached at startup;
 * backtest answers from configuration or the dataset. The risk gate asks this interface and
 * never knows which one it got, so the same sizing and rounding code runs in all three modes
 * (FR-BT-06).
 */
public interface TradingRulesProvider {

    /**
     * Rules for a symbol, or empty when they are not known. Empty must block the order:
     * without tick/step/minNotional there is no way to avoid sending a dirty one.
     */
    Optional<TradingRules> find(Symbol symbol);
}
