package com.ciaozn.alphatrader.app.config;

import com.ciaozn.alphatrader.common.model.Interval;
import com.ciaozn.alphatrader.common.model.Symbol;
import com.ciaozn.alphatrader.engine.EventEngine;
import com.ciaozn.alphatrader.gateway.ExchangeGateway;
import com.ciaozn.alphatrader.gateway.binance.BinanceFuturesGateway;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Market data gateway assembly for online modes. The same class serves paper and live -
 * the ONLY difference is the testnet flag (FR-GW-05). Backtest has no gateway bean:
 * its data source is the feeder (P2), proving the replaceable-parts design.
 */
@Configuration
@Profile({"paper", "live"})
public class GatewayWiringConfig {

    @Bean(destroyMethod = "close")
    ExchangeGateway binanceGateway(EventEngine engine, AlphaProperties properties) {
        BinanceFuturesGateway gateway = new BinanceFuturesGateway(engine);
        Interval interval = Interval.fromBinanceCode(properties.interval());
        properties.symbols().forEach(s -> gateway.subscribeKline(Symbol.parse(s), interval));
        return gateway;
    }
}
