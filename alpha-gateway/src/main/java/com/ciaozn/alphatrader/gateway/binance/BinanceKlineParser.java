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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Pure functions: Binance USDⓈ-M kline payload -> standardized model. Both shapes the exchange
 * uses are parsed here so its field names and column indices live in exactly one place: the
 * WebSocket object ({@code {"data":{"k":{...}}}}) and the REST {@code /fapi/v1/klines} array of
 * arrays. No network, no state - fully unit-testable (T109 acceptance).
 *
 * <p>Business timestamp = bar close time (k.T): a closed bar "happens" at its close,
 * which keeps the anti-look-ahead discipline consistent between live and backtest.
 */
public final class BinanceKlineParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** REST columns read, in order: openTime, open, high, low, close, volume, closeTime. */
    private static final int REST_COLUMNS = 7;

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

    /**
     * REST {@code /fapi/v1/klines} response: an array of rows, each
     * {@code [openTime, open, high, low, close, volume, closeTime, ...]} with further columns
     * this system does not use. Order is preserved - the exchange returns ascending bars and
     * the repository relies on that.
     *
     * <p>The last row may be the bar that is still forming; deciding that needs the current
     * time, so it is the caller's job (see {@code BinanceKlineDownloader}), not the parser's.
     */
    public static List<Kline> parseKlines(String payload) {
        JsonNode root;
        try {
            root = MAPPER.readTree(payload);
        } catch (IOException e) {
            throw new UncheckedIOException("Malformed kline array payload", e);
        }
        if (!root.isArray()) {
            throw new IllegalArgumentException("Not a kline array: " + payload);
        }
        List<Kline> klines = new ArrayList<>(root.size());
        for (JsonNode row : root) {
            if (!row.isArray() || row.size() < REST_COLUMNS) {
                throw new IllegalArgumentException("Malformed kline row (need at least "
                        + REST_COLUMNS + " columns): " + row);
            }
            klines.add(new Kline(
                    row.get(0).asLong(),
                    new BigDecimal(row.get(1).asText()),
                    new BigDecimal(row.get(2).asText()),
                    new BigDecimal(row.get(3).asText()),
                    new BigDecimal(row.get(4).asText()),
                    new BigDecimal(row.get(5).asText()),
                    row.get(6).asLong()));
        }
        return Collections.unmodifiableList(klines);
    }
}
