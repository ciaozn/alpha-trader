package com.ciaozn.alphatrader.app.config;

import com.ciaozn.alphatrader.common.model.Interval;
import com.ciaozn.alphatrader.common.model.Symbol;
import com.ciaozn.alphatrader.engine.EventEngine;
import com.ciaozn.alphatrader.gateway.ExchangeGateway;
import com.ciaozn.alphatrader.gateway.binance.BinanceFuturesGateway;
import com.ciaozn.alphatrader.gateway.okx.OkxSwapGateway;
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
 *
 * <p><b>Which exchange is attached comes from {@code alpha.online.gateway}</b> (T406, FR-GW-06).
 * The subscription loop below is deliberately written against {@link ExchangeGateway} rather than
 * either implementation, so adding the second exchange changed no assembly logic - which is the
 * test of whether the abstraction was worth having.
 */
@Configuration
@Profile({"paper", "live"})
public class GatewayWiringConfig {

    private static final Logger log = LoggerFactory.getLogger(GatewayWiringConfig.class);

    @Bean(destroyMethod = "close")
    ExchangeGateway exchangeGateway(EventEngine engine, StrategyEngine strategyEngine,
                                    AlphaProperties properties, OnlineProperties online) {
        ExchangeGateway gateway = createGateway(online.gateway(), engine);
        Set<String> subscribed = new LinkedHashSet<>();
        Interval globalInterval = Interval.fromBinanceCode(properties.interval());
        properties.symbols().forEach(symbol ->
                subscribe(gateway, Symbol.parse(symbol), globalInterval, subscribed));
        for (Strategy strategy : strategyEngine.strategies()) {
            strategy.symbols().forEach(symbol ->
                    subscribe(gateway, symbol, strategy.interval(), subscribed));
        }
        log.info("{} gateway subscribed to market data: {}", online.gateway(), subscribed);
        return gateway;
    }

    /** Exposed so a test can assert the property actually selects an implementation. */
    static ExchangeGateway createGateway(OnlineProperties.GatewayType type, EventEngine engine) {
        return switch (type) {
            case BINANCE -> new BinanceFuturesGateway(engine);
            case OKX -> new OkxSwapGateway(engine);
        };
    }

    private static void subscribe(ExchangeGateway gateway, Symbol symbol, Interval interval,
                                  Set<String> subscribed) {
        // The dedupe key is the internal interval name, not an exchange code: the same subscription
        // must collapse to one entry regardless of which exchange names it differently.
        if (subscribed.add(symbol.unified() + "/" + interval.name())) {
            gateway.subscribeKline(symbol, interval);
        }
    }
}
