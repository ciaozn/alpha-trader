package com.ciaozn.alphatrader.gateway.binance;

import com.ciaozn.alphatrader.common.event.KlineEvent;
import com.ciaozn.alphatrader.common.model.Interval;
import com.ciaozn.alphatrader.common.model.Kline;
import com.ciaozn.alphatrader.common.model.Symbol;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;

/**
 * Pure function: Binance USDⓈ-M kline WebSocket payload -> standardized KlineEvent.
 * No network, no state - fully unit-testable (T109 acceptance).
 *
 * <p>Business timestamp = bar close time (k.T): a closed bar "happens" at its close,
 * which keeps the anti-look-ahead discipline consistent between live and backtest.
 */
public final class BinanceKlineParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private BinanceKlineParser() {
    }

    public static KlineEvent parse(String payload) {
        try {
            JsonNode root = MAPPER.readTree(payload);
            JsonNode data = root.path("data");
            JsonNode k = data.path("k");
            if (k.isMissingNode()) {
                throw new IllegalArgumentException("Not a kline payload: " + payload);
            }

            Symbol symbol = Symbol.parse(k.path("s").asText() + ".PERP");
            Interval interval = Interval.fromBinanceCode(k.path("i").asText());
            boolean closed = k.path("x").asBoolean(false);

            Kline kline = new Kline(
                    k.path("t").asLong(),
                    new BigDecimal(k.path("o").asText()),
                    new BigDecimal(k.path("h").asText()),
                    new BigDecimal(k.path("l").asText()),
                    new BigDecimal(k.path("c").asText()),
                    new BigDecimal(k.path("v").asText()),
                    k.path("T").asLong());

            return KlineEvent.of(symbol, interval, kline, closed, kline.closeTime());
        } catch (IOException e) {
            throw new UncheckedIOException("Malformed kline payload", e);
        }
    }
}
