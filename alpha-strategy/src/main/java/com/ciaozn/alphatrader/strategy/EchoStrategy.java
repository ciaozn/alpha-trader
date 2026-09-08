package com.ciaozn.alphatrader.strategy;

import com.ciaozn.alphatrader.common.event.Event;
import com.ciaozn.alphatrader.common.event.KlineEvent;
import com.ciaozn.alphatrader.engine.EventHandler;
import com.ciaozn.alphatrader.engine.EventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * P1 placeholder strategy: proves the market-data -> engine -> handler path end to end
 * by logging every received k-line. Real strategies (MA cross / RSI) arrive in P2
 * behind the Strategy SPI - this class will be deleted then.
 */
public final class EchoStrategy implements EventHandler {

    private static final Logger log = LoggerFactory.getLogger(EchoStrategy.class);

    @Override
    public void onEvent(Event event, EventPublisher publisher) {
        if (event instanceof KlineEvent k) {
            log.info("KlineEvent[symbol={}, interval={}, closed={}, close={} @ {}]",
                    k.symbol().unified(), k.interval(), k.closed(),
                    k.kline().close(), k.kline().closeTime());
        }
    }
}
