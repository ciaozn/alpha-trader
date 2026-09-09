package com.ciaozn.alphatrader.gateway;

import com.ciaozn.alphatrader.common.model.Interval;
import com.ciaozn.alphatrader.common.model.Kline;
import com.ciaozn.alphatrader.common.model.Symbol;
import com.ciaozn.alphatrader.gateway.binance.BinanceKlineDownloader;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Drives the downloader against a stub HTTP server on loopback rather than mocks: paging,
 * retry and status handling are all about the real request/response cycle, and a stub lets the
 * test script exactly which pages and which failure codes the exchange "sends".
 */
class BinanceKlineDownloaderTest {

    private static final Symbol BTC = Symbol.parse("BTCUSDT.PERP");
    private static final long T0 = 1_700_000_000_000L;
    private static final long HOUR = 3_600_000L;
    /** Far enough past every bar in these tests that nothing counts as still forming. */
    private static final long FAR_FUTURE = T0 + 1_000 * HOUR;

    private StubServer stub;
    private OkHttpClient http;

    @BeforeEach
    void startStub() throws IOException {
        stub = new StubServer();
        http = new OkHttpClient();
    }

    @AfterEach
    void stopStub() {
        stub.close();
        http.dispatcher().executorService().shutdown();
        http.connectionPool().evictAll();
    }

    private BinanceKlineDownloader downloader(int pageSize, long now) {
        return downloader(pageSize, now, 3);
    }

    private BinanceKlineDownloader downloader(int pageSize, long now, int maxAttempts) {
        BinanceKlineDownloader.Settings settings = new BinanceKlineDownloader.Settings(
                stub.baseUrl(), pageSize, 0L, 1L, maxAttempts);
        return new BinanceKlineDownloader(http, settings, () -> now);
    }

    private static Kline bar(long openTime, String close) {
        BigDecimal price = new BigDecimal(close);
        return new Kline(openTime, price.subtract(BigDecimal.ONE), price.add(BigDecimal.ONE),
                price.subtract(new BigDecimal("2")), price, new BigDecimal("10.5"), openTime + HOUR - 1);
    }

    /** Bar values derive from the absolute open time, so a bar is the same on every page. */
    private static List<Kline> barsFrom(long openTime, int count) {
        List<Kline> bars = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            long barOpenTime = openTime + index * HOUR;
            bars.add(bar(barOpenTime, "30000." + (10 + (barOpenTime - T0) / HOUR)));
        }
        return bars;
    }

    /** Binance's own wire shape, so the test proves the parser and the downloader agree. */
    private static String json(List<Kline> bars) {
        StringBuilder out = new StringBuilder("[");
        for (int index = 0; index < bars.size(); index++) {
            Kline kline = bars.get(index);
            if (index > 0) {
                out.append(',');
            }
            out.append('[').append(kline.openTime())
                    .append(',').append(quoted(kline.open()))
                    .append(',').append(quoted(kline.high()))
                    .append(',').append(quoted(kline.low()))
                    .append(',').append(quoted(kline.close()))
                    .append(',').append(quoted(kline.volume()))
                    .append(',').append(kline.closeTime())
                    .append(",\"0\",0,\"0\",\"0\",\"0\"]");
        }
        return out.append(']').toString();
    }

    private static String quoted(BigDecimal value) {
        return "\"" + value.toPlainString() + "\"";
    }

    @Test
    void pagesForwardUntilTheRangeIsCovered() {
        stub.script(200, json(barsFrom(T0, 2)), null);
        stub.script(200, json(barsFrom(T0 + 2 * HOUR, 2)), null);
        stub.script(200, json(barsFrom(T0 + 4 * HOUR, 1)), null);
        InMemoryKlineRepository repository = new InMemoryKlineRepository();

        BinanceKlineDownloader.DownloadResult result =
                downloader(2, FAR_FUTURE).download(repository, BTC, Interval.H1, T0, T0 + 9 * HOUR);

        assertThat(result.requests()).isEqualTo(3);
        assertThat(result.fetched()).isEqualTo(5);
        assertThat(result.stored()).isEqualTo(5);
        assertThat(result.skippedForming()).isZero();
        assertThat(stub.queries().get("startTime")).containsExactly(
                Long.toString(T0), Long.toString(T0 + 2 * HOUR), Long.toString(T0 + 4 * HOUR));
        assertThat(stub.queries().get("endTime"))
                .containsExactly(Long.toString(T0 + 9 * HOUR), Long.toString(T0 + 9 * HOUR),
                        Long.toString(T0 + 9 * HOUR));
        assertThat(stub.queries().get("symbol")).containsExactly("BTCUSDT", "BTCUSDT", "BTCUSDT");
        assertThat(stub.queries().get("interval")).containsExactly("1h", "1h", "1h");
        assertThat(stub.queries().get("limit")).containsExactly("2", "2", "2");
        assertThat(repository.loadAll(BTC, Interval.H1)).isEqualTo(barsFrom(T0, 5));
    }

    @Test
    void stopsWhenTheExchangeHasNothingMore() {
        stub.script(200, json(barsFrom(T0, 2)), null);
        stub.script(200, "[]", null);
        InMemoryKlineRepository repository = new InMemoryKlineRepository();

        BinanceKlineDownloader.DownloadResult result =
                downloader(2, FAR_FUTURE).download(repository, BTC, Interval.H1, T0, T0 + 100 * HOUR);

        // an empty page must end the loop: endTime is past the last bar the exchange holds
        assertThat(result.requests()).isEqualTo(2);
        assertThat(repository.loadAll(BTC, Interval.H1)).hasSize(2);
    }

    @Test
    void neverStoresTheBarThatIsStillForming() {
        stub.script(200, json(barsFrom(T0, 2)), null);
        InMemoryKlineRepository repository = new InMemoryKlineRepository();

        // "now" sits inside the second bar, so only the first one has really closed
        BinanceKlineDownloader.DownloadResult result =
                downloader(1500, T0 + HOUR).download(repository, BTC, Interval.H1, T0, T0 + 10 * HOUR);

        assertThat(result.fetched()).isEqualTo(2);
        assertThat(result.stored()).isEqualTo(1);
        assertThat(result.skippedForming()).isEqualTo(1);
        assertThat(repository.loadAll(BTC, Interval.H1)).containsExactly(bar(T0, "30000.10"));
    }

    @Test
    void reRunningOverTheSameRangeStoresNothing() {
        stub.script(200, json(barsFrom(T0, 2)), null);
        stub.script(200, json(barsFrom(T0, 2)), null);
        InMemoryKlineRepository repository = new InMemoryKlineRepository();
        BinanceKlineDownloader downloader = downloader(1500, FAR_FUTURE);

        assertThat(downloader.download(repository, BTC, Interval.H1, T0, T0 + HOUR).stored()).isEqualTo(2);
        BinanceKlineDownloader.DownloadResult rerun =
                downloader.download(repository, BTC, Interval.H1, T0, T0 + HOUR);

        assertThat(rerun.fetched()).isEqualTo(2);
        assertThat(rerun.stored()).isZero();
        assertThat(repository.loadAll(BTC, Interval.H1)).hasSize(2);
    }

    @Test
    void retriesARateLimitedRequestAndHonoursRetryAfter() {
        stub.script(429, "{\"code\":-1003,\"msg\":\"Too many requests\"}", "0");
        stub.script(200, json(barsFrom(T0, 1)), null);
        InMemoryKlineRepository repository = new InMemoryKlineRepository();

        BinanceKlineDownloader.DownloadResult result =
                downloader(1500, FAR_FUTURE).download(repository, BTC, Interval.H1, T0, T0 + HOUR);

        // two HTTP calls - the 429 and the retry - but still one page of data
        assertThat(stub.requestCount()).isEqualTo(2);
        assertThat(result.requests()).isEqualTo(1);
        assertThat(result.stored()).isEqualTo(1);
    }

    @Test
    void waitsAsLongAsTheExchangeAsksBeforeRetrying() {
        stub.script(429, "{\"code\":-1003,\"msg\":\"Too many requests\"}", "1");
        stub.script(200, json(barsFrom(T0, 1)), null);
        InMemoryKlineRepository repository = new InMemoryKlineRepository();

        long started = System.nanoTime();
        downloader(1500, FAR_FUTURE).download(repository, BTC, Interval.H1, T0, T0 + HOUR);
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;

        // our own backoff starts at 1 ms in this harness, so only Retry-After explains a full second
        assertThat(elapsedMillis).isGreaterThanOrEqualTo(1000L);
        assertThat(repository.loadAll(BTC, Interval.H1)).hasSize(1);
    }

    @Test
    void failsFastOnAClientErrorWithoutRetrying() {
        stub.script(400, "{\"code\":-1121,\"msg\":\"Invalid symbol.\"}", null);
        InMemoryKlineRepository repository = new InMemoryKlineRepository();

        assertThatThrownBy(() -> downloader(1500, FAR_FUTURE).download(repository, BTC, Interval.H1, T0, T0 + HOUR))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HTTP 400")
                .hasMessageContaining("Invalid symbol.");

        assertThat(stub.requestCount()).isEqualTo(1);
    }

    @Test
    void givesUpAfterTheAttemptBudget() {
        for (int attempt = 0; attempt < 3; attempt++) {
            stub.script(503, "{\"msg\":\"Service unavailable\"}", null);
        }
        InMemoryKlineRepository repository = new InMemoryKlineRepository();

        assertThatThrownBy(() -> downloader(1500, FAR_FUTURE).download(repository, BTC, Interval.H1, T0, T0 + HOUR))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HTTP 503");

        assertThat(stub.requestCount()).isEqualTo(3);
        assertThat(repository.loadAll(BTC, Interval.H1)).isEmpty();
    }

    @Test
    void anEmptyFirstPageIsNotAFailure() {
        stub.script(200, "[]", null);
        InMemoryKlineRepository repository = new InMemoryKlineRepository();

        BinanceKlineDownloader.DownloadResult result =
                downloader(1500, FAR_FUTURE).download(repository, BTC, Interval.H1, T0, T0 + HOUR);

        assertThat(result.requests()).isEqualTo(1);
        assertThat(result.fetched()).isZero();
        assertThat(result.stored()).isZero();
    }

    @Test
    void rejectsAnImpossibleRange() {
        BinanceKlineDownloader downloader = downloader(1500, FAR_FUTURE);
        InMemoryKlineRepository repository = new InMemoryKlineRepository();

        assertThatThrownBy(() -> downloader.download(repository, BTC, Interval.H1, T0 + HOUR, T0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("startTime");
        assertThatThrownBy(() -> downloader.download(repository, BTC, Interval.H1, -1, T0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(stub.requestCount()).isZero();
    }

    @Test
    void settingsAreValidatedAndNormalized() {
        assertThat(new BinanceKlineDownloader.Settings(stub.baseUrl() + "/", 10, 0, 1, 1).baseUrl())
                .isEqualTo(stub.baseUrl());
        assertThat(BinanceKlineDownloader.Settings.TESTNET.baseUrl())
                .isEqualTo(BinanceKlineDownloader.TESTNET_BASE_URL);
        assertThat(BinanceKlineDownloader.Settings.LIVE.baseUrl())
                .isEqualTo(BinanceKlineDownloader.LIVE_BASE_URL);

        assertThatThrownBy(() -> new BinanceKlineDownloader.Settings(" ", 10, 0, 1, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("baseUrl");
        assertThatThrownBy(() -> new BinanceKlineDownloader.Settings(stub.baseUrl(), 0, 0, 1, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pageSize");
        assertThatThrownBy(() -> new BinanceKlineDownloader.Settings(stub.baseUrl(),
                BinanceKlineDownloader.MAX_PAGE_SIZE + 1, 0, 1, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1500");
        assertThatThrownBy(() -> new BinanceKlineDownloader.Settings(stub.baseUrl(), 10, -1, 1, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("delays");
        assertThatThrownBy(() -> new BinanceKlineDownloader.Settings(stub.baseUrl(), 10, 0, 1, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxAttempts");
    }

    // ------------------------------------------------------------------ stubs

    /** One scripted HTTP response per kline request; extra requests get an empty page. */
    private record StubResponse(int status, String body, String retryAfter) {
    }

    private static final class StubServer implements AutoCloseable {

        private final HttpServer server;
        private final Deque<StubResponse> scripted = new ArrayDeque<>();
        private final List<Map<String, String>> requests = new ArrayList<>();

        private StubServer() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/fapi/v1/klines", this::handle);
            server.start();
        }

        private String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        private void script(int status, String body, String retryAfter) {
            scripted.add(new StubResponse(status, body, retryAfter));
        }

        private int requestCount() {
            return requests.size();
        }

        /** Query parameters per request, so paging can be asserted on what actually went out. */
        private Map<String, List<String>> queries() {
            Map<String, List<String>> byName = new LinkedHashMap<>();
            for (Map<String, String> request : requests) {
                request.forEach((name, value) ->
                        byName.computeIfAbsent(name, unused -> new ArrayList<>()).add(value));
            }
            return byName;
        }

        private void handle(HttpExchange exchange) throws IOException {
            requests.add(parseQuery(exchange.getRequestURI().getRawQuery()));
            StubResponse response = scripted.isEmpty()
                    ? new StubResponse(200, "[]", null)
                    : scripted.pop();
            byte[] body = response.body().getBytes(StandardCharsets.UTF_8);
            if (response.retryAfter() != null) {
                exchange.getResponseHeaders().add("Retry-After", response.retryAfter());
            }
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(response.status(), body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
            exchange.close();
        }

        private static Map<String, String> parseQuery(String query) {
            Map<String, String> parameters = new LinkedHashMap<>();
            if (query == null || query.isBlank()) {
                return parameters;
            }
            for (String pair : query.split("&")) {
                int separator = pair.indexOf('=');
                parameters.put(pair.substring(0, separator), pair.substring(separator + 1));
            }
            return parameters;
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
