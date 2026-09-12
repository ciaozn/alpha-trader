package com.ciaozn.alphatrader.app.config;

import com.ciaozn.alphatrader.common.time.VirtualClock;
import com.ciaozn.alphatrader.engine.EventEngine;
import com.ciaozn.alphatrader.engine.EventJournal;
import com.ciaozn.alphatrader.gateway.ExchangeGateway;
import com.ciaozn.alphatrader.gateway.binance.BinanceFuturesGateway;
import com.ciaozn.alphatrader.gateway.okx.OkxSwapGateway;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T406's remaining gap, closed: the OKX gateway is not just implemented, it is reachable by
 * configuration. Before this, {@code OkxSwapGateway} existed with passing tests and no way to turn
 * it on - the same shape of gap the online wiring had before T319, and the reason "it has tests" is
 * not the same claim as "it is wired".
 */
class GatewayWiringTest {

    private final EventEngine engine = new EventEngine(EventJournal.noop(), new VirtualClock(0L));

    @Test
    void thePropertySelectsTheImplementation() {
        ExchangeGateway binance = GatewayWiringConfig.createGateway(OnlineProperties.GatewayType.BINANCE, engine);
        ExchangeGateway okx = GatewayWiringConfig.createGateway(OnlineProperties.GatewayType.OKX, engine);

        assertThat(binance).isInstanceOf(BinanceFuturesGateway.class);
        assertThat(okx).isInstanceOf(OkxSwapGateway.class);
    }

    @Test
    void anAbsentPropertyDefaultsToBinance() {
        // The default must be the exchange the rest of the project was built and verified against.
        assertThat(new OnlineProperties(null, null, null, null).gateway())
                .isEqualTo(OnlineProperties.GatewayType.BINANCE);
    }

    @Test
    void anAbsentEquityCapDefaultsToTheSpecCeiling() {
        // SC-06: 1000 USDT, not "unlimited" - an unset property must fail safe.
        assertThat(new OnlineProperties(null, null, null, null).maxEquity())
                .isEqualByComparingTo(OnlineProperties.DEFAULT_MAX_EQUITY);
    }
}
