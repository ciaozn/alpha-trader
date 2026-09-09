package com.ciaozn.alphatrader.app;

import com.ciaozn.alphatrader.common.model.Kline;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Binance's public {@code /fapi/v1/klines} endpoint on localhost, for the tests that have to run the
 * real download path without an internet connection (T223).
 *
 * <p>The JDK's own HTTP server rather than a mocking library or a new test dependency: a wiring test
 * that needs the internet fails for reasons that have nothing to do with the wiring - a rate limit, a
 * renamed symbol, a machine with no route out - and a test that fails unpredictably teaches people to
 * re-run it instead of read it. The real exchange is covered by
 * {@code BinanceKlineDownloaderTestnetTest}, which is opt-in for exactly that reason.
 *
 * <p>It records every query it is asked, and it serves only the bars inside the requested window, so
 * a swapped or truncated range comes back as fewer bars rather than being quietly accepted.
 */
public final class StubKlineExchange implements AutoCloseable {

    private final HttpServer server;
    private final List<Map<String, String>> requests = new ArrayList<>();
    private final List<Kline> bars;
    private int status = 200;

    public StubKlineExchange(List<Kline> bars) throws IOException {
        this.bars = bars;
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext("/fapi/v1/klines", this::respond);
        this.server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Every query this stub has been asked, in order, as parsed name-value pairs. */
    public List<Map<String, String>> requests() {
        return requests;
    }

    /**
     * Answers every later request with this status and an error body instead of bars. A 4xx rather
     * than a dead socket when a test needs a failure: the downloader retries a connection error five
     * times with doubling backoff, which would turn one assertion into fifteen seconds.
     */
    public void failWith(int status) {
        this.status = status;
    }

    @Override
    public void close() {
        // stop(0) also ends the dispatch thread, which is not a daemon one: leaving a stub running
        // would leave a non-daemon thread in the JVM, which is the very thing these tests assert on.
        server.stop(0);
    }

    private void respond(HttpExchange exchange) throws IOException {
        Map<String, String> query = query(exchange.getRequestURI().getRawQuery());
        requests.add(query);
        byte[] body;
        if (status == 200) {
            long from = Long.parseLong(query.get("startTime"));
            long to = Long.parseLong(query.get("endTime"));
            body = json(bars.stream()
                    .filter(bar -> bar.openTime() >= from && bar.openTime() <= to)
                    .toList()).getBytes(StandardCharsets.UTF_8);
        } else {
            body = "{\"code\":-1121,\"msg\":\"Invalid symbol.\"}".getBytes(StandardCharsets.UTF_8);
        }
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> query = new LinkedHashMap<>();
        for (String pair : raw.split("&")) {
            int equals = pair.indexOf('=');
            query.put(pair.substring(0, equals), pair.substring(equals + 1));
        }
        return query;
    }

    /** The REST shape the parser reads: the first seven columns of Binance's array of arrays. */
    private static String json(List<Kline> bars) {
        StringBuilder out = new StringBuilder("[");
        for (int index = 0; index < bars.size(); index++) {
            Kline bar = bars.get(index);
            if (index > 0) {
                out.append(',');
            }
            out.append('[').append(bar.openTime())
                    .append(',').append(quoted(bar.open()))
                    .append(',').append(quoted(bar.high()))
                    .append(',').append(quoted(bar.low()))
                    .append(',').append(quoted(bar.close()))
                    .append(',').append(quoted(bar.volume()))
                    .append(',').append(bar.closeTime())
                    .append(']');
        }
        return out.append(']').toString();
    }

    /** Quoted, because the parser reads prices with {@code asText()} and precision is the point. */
    private static String quoted(BigDecimal value) {
        return '"' + value.toPlainString() + '"';
    }

    /** Ascending bars one {@code step} apart from {@code openTime}, each closing above its open. */
    public static List<Kline> bars(int count, long openTime, long step) {
        List<Kline> bars = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            BigDecimal open = BigDecimal.valueOf(100 + index * 10L);
            BigDecimal close = open.add(BigDecimal.ONE);
            bars.add(new Kline(openTime + index * step, open, close.add(new BigDecimal("0.5")),
                    open.subtract(new BigDecimal("0.5")), close, new BigDecimal("10"),
                    openTime + (index + 1) * step - 1));
        }
        return bars;
    }
}
