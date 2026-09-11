package com.ciaozn.alphatrader.gateway.binance;

import com.ciaozn.alphatrader.common.event.OrderReportEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The private WebSocket that carries order and execution facts (T317, FR-EX-03): one
 * {@code listenKey}, opened at connect, kept alive every 30 minutes, and re-opened with backoff if
 * it drops.
 *
 * <p><b>Everything it learns is published as an {@link OrderReportEvent} and nothing else.</b> It
 * never touches the OMS, never writes to a store and never emits a {@code FillEvent}. The exchange
 * is a second source of truth about our orders, and the OMS is the component that reconciles the two
 * - a stream that applied fills directly would leave the order book and the event log disagreeing
 * about whether an execution happened.
 *
 * <p><b>Keepalive is a separate scheduled task, not an engine timer.</b> It is a blocking REST call
 * and must never run on the event loop; its cadence (30 minutes, Binance's expiry is 60) is a
 * property of the stream, not of the trading logic.
 *
 * <p>A dropped stream is not fatal on its own: reconciliation (T318) queries the exchange directly,
 * so the worst case is corrections arriving up to one reconciliation period late rather than being
 * missed.
 */
public final class BinanceUserDataStream implements AutoCloseable {

    /** Binance expires a listenKey after 60 minutes; we refresh at half that (FR-EX-03). */
    public static final Duration KEEPALIVE_INTERVAL = Duration.ofMinutes(30);

    private static final Logger log = LoggerFactory.getLogger(BinanceUserDataStream.class);
    private static final String LISTEN_KEY_PATH = "fapi/v1/listenKey";

    private final BinanceTransport transport;
    private final String baseUrl;
    private final String wsBaseUrl;
    private final Map<String, String> headers;
    private final java.util.function.Consumer<OrderReportEvent> sink;
    private final ScheduledExecutorService keepalive;
    private final BackoffPolicy backoff = new BackoffPolicy();

    private volatile boolean running;
    private volatile String listenKey;
    private WebSocketClient socket;
    private Thread supervisor;

    /**
     * @param sink receives every parsed report; the gateway hands it {@code engine::publish}, so a
     *             test can collect reports without a running engine
     */
    public BinanceUserDataStream(BinanceTransport transport, String baseUrl, String wsBaseUrl,
                                 Map<String, String> headers,
                                 java.util.function.Consumer<OrderReportEvent> sink) {
        this.transport = transport;
        this.baseUrl = baseUrl;
        this.wsBaseUrl = wsBaseUrl;
        this.headers = headers;
        this.sink = sink;
        this.keepalive = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "binance-listenkey-keepalive");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void start() {
        if (running) {
            return;
        }
        running = true;
        keepalive.scheduleAtFixedRate(this::keepAliveSafely,
                KEEPALIVE_INTERVAL.toMinutes(), KEEPALIVE_INTERVAL.toMinutes(), TimeUnit.MINUTES);
        supervisor = new Thread(this::supervise, "binance-user-data");
        supervisor.setDaemon(true);
        supervisor.start();
        log.info("User data stream starting against {}", wsBaseUrl);
    }

    private void supervise() {
        while (running) {
            try {
                listenKey = createListenKey();
                connectOnce(listenKey);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.warn("User data stream connection failed", e);
            }
            if (running) {
                long delay = backoff.nextDelayMillis();
                log.info("Reconnecting user data stream in {} ms", delay);
                sleep(delay);
            }
        }
    }

    private void connectOnce(String key) throws InterruptedException {
        java.util.concurrent.CountDownLatch closed = new java.util.concurrent.CountDownLatch(1);
        URI uri = URI.create(wsBaseUrl + "/" + key);
        socket = new WebSocketClient(uri) {
            @Override
            public void onOpen(ServerHandshake handshake) {
                backoff.reset();
                log.info("User data stream connected to {}", uri.getHost());
            }

            @Override
            public void onMessage(String message) {
                Optional<OrderReportEvent> report;
                try {
                    report = BinanceUserDataParser.parse(message);
                } catch (RuntimeException e) {
                    // One unparseable frame must not cost us the stream: reconciliation is the
                    // safety net, not this catch.
                    log.warn("Dropping unparseable user data frame: {}", e.getMessage());
                    return;
                }
                report.ifPresent(sink);
            }

            @Override
            public void onClose(int code, String reason, boolean remote) {
                log.warn("User data stream closed: code={} reason={} remote={}", code, reason, remote);
                closed.countDown();
            }

            @Override
            public void onError(Exception ex) {
                log.error("User data stream error", ex);
            }
        };
        socket.connectBlocking(10, TimeUnit.SECONDS);
        closed.await();
    }

    /** POST /fapi/v1/listenKey - keyed but unsigned; the key is what identifies the account. */
    String createListenKey() {
        String body = transport.call("POST", baseUrl + "/" + LISTEN_KEY_PATH, headers);
        listenKey = readListenKey(body);
        return listenKey;
    }

    /** PUT /fapi/v1/listenKey - extends the expiry of the key we are already streaming. */
    void keepAlive() {
        String key = listenKey;
        if (key == null || key.isBlank()) {
            return;
        }
        transport.call("PUT", baseUrl + "/" + LISTEN_KEY_PATH, headers);
    }

    private void keepAliveSafely() {
        try {
            keepAlive();
        } catch (RuntimeException e) {
            log.warn("listenKey keepalive failed; the supervisor will re-open the stream if it drops", e);
        }
    }

    private static String readListenKey(String body) {
        try {
            JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
            String key = node.path("listenKey").asText(null);
            if (key == null || key.isBlank()) {
                throw new IllegalStateException("listenKey response carries no key: " + body);
            }
            return key;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Malformed listenKey response: " + body, e);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Cancels keepalive, closes the socket and tears the key down so it cannot outlive this process. */
    @Override
    public void close() {
        running = false;
        keepalive.shutdownNow();
        if (socket != null) {
            socket.close();
        }
        if (supervisor != null) {
            supervisor.interrupt();
        }
        if (listenKey != null && !listenKey.isBlank()) {
            try {
                transport.call("DELETE", baseUrl + "/" + LISTEN_KEY_PATH, headers);
            } catch (RuntimeException e) {
                log.warn("listenKey teardown failed", e);
            }
        }
        log.info("User data stream closed");
    }
}
