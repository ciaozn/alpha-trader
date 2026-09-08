package com.ciaozn.alphatrader.app.config;

import com.ciaozn.alphatrader.common.model.Interval;
import com.ciaozn.alphatrader.common.model.Symbol;
import com.ciaozn.alphatrader.engine.EventEngine;
import com.ciaozn.alphatrader.gateway.ExchangeGateway;
import com.ciaozn.alphatrader.gateway.binance.BinanceFuturesGateway;
import com.ciaozn.alphatrader.strategy.Strategy;
import com.ciaozn.alphatrader.strategy.StrategyEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Market data gateway assembly for online modes. The same class serves paper and live -
 * the ONLY difference is the testnet flag (FR-GW-05). Backtest has no gateway bean:
 * its data source is the feeder (P2), proving the replaceable-parts design.
 *
 * <p>Subscriptions are the union of {@code alpha.symbols} and whatever the enabled strategies
 * declared. Without the second half, a strategy enabled on a symbol nobody listed would sit
 * silently waiting for bars that never arrive (FR-ST-01: configuration decides what runs).
 */
@Configuration
@Profile({"paper", "live"})
public class GatewayWiringConfig {

    private static final Logger log = LoggerFactory.getLogger(GatewayWiringConfig.class);

    @Bean(destroyMethod = "close")
    ExchangeGateway binanceGateway(EventEngine engine, StrategyEngine strategyEngine, AlphaProperties properties) {
        BinanceFuturesGateway gateway = new BinanceFuturesGateway(engine);
        Set<String> subscribed = new LinkedHashSet<>();
        Interval globalInterval = Interval.fromBinanceCode(properties.interval());
        properties.symbols().forEach(symbol ->
                subscribe(gateway, Symbol.parse(symbol), globalInterval, subscribed));
        for (Strategy strategy : strategyEngine.strategies()) {
            strategy.symbols().forEach(symbol ->
                    subscribe(gateway, symbol, strategy.interval(), subscribed));
        }
        log.info("Subscribed market data: {}", subscribed);
        return gateway;
    }

    private static void subscribe(BinanceFuturesGateway gateway, Symbol symbol, Interval interval,
                                  Set<String> subscribed) {
        if (subscribed.add(symbol.unified() + "/" + interval.binanceCode())) {
            gateway.subscribeKline(symbol, interval);
        }
    }
}
