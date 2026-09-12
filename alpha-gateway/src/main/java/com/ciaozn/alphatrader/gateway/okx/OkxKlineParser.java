package com.ciaozn.alphatrader.gateway.okx;

import com.ciaozn.alphatrader.common.event.KlineEvent;
import com.ciaozn.alphatrader.common.model.Interval;
import com.ciaozn.alphatrader.common.model.Kline;
import com.ciaozn.alphatrader.common.model.Symbol;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.Optional;

/**
 * OKX v5 candlestick push -> {@link KlineEvent} (T406).
 *
 * <p>Two shape differences from Binance, both handled here so nothing above the gateway knows about
 * them: the channel is subscribed per interval ({@code candle1H}) so the interval has to be carried
 * into the parser, and the bar arrives as a positional array
 * {@code [ts, o, h, l, c, vol, volCcy, volCcyQuote, confirm]} in which "closed" is a string flag.
 *
 * <p>An unconfirmed bar is still published - strategies filter on {@link KlineEvent#closed()}, and
 * dropping it here would make the live feed quietly different from the backtest feed.
 */
public final class OkxKlineParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private OkxKlineParser() {
    }

    public static Optional<KlineEvent> parse(String payload, Interval interval) {
        try {
            JsonNode root = MAPPER.readTree(payload);
            JsonNode data = root.get("data");
            if (data == null || !data.isArray() || data.isEmpty()) {
                return Optional.empty();
            }
            JsonNode candle = data.get(0);
            String instId = candle.path("instId").asText(null);
            JsonNode bar = candle.get("candle");
            if (instId == null || bar == null) {
                // Not a candle frame (subscribe ack, error, or another channel): ignoring it is the
                // contract - the gateway must not turn an unknown frame into a price.
                return Optional.empty();
            }
            Symbol symbol = OkxSymbols.toUnified(instId);
            boolean closed = "1".equals(bar.path(8).asText("0"));
            Kline kline = new Kline(
                    bar.path(0).asLong(),
                    new BigDecimal(bar.path(1).asText()),
                    new BigDecimal(bar.path(2).asText()),
                    new BigDecimal(bar.path(3).asText()),
                    new BigDecimal(bar.path(4).asText()),
                    new BigDecimal(bar.path(5).asText()),
                    bar.path(0).asLong() + interval.duration().toMillis() - 1);
            return Optional.of(KlineEvent.of(symbol, interval, kline, closed, kline.closeTime()));
        } catch (IOException e) {
            throw new UncheckedIOException("Malformed OKX candle frame", e);
        }
    }
}
