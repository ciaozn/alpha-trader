package com.ciaozn.alphatrader.gateway.binance;

import com.ciaozn.alphatrader.gateway.BackoffPolicy;
import com.ciaozn.alphatrader.common.event.OrderRequestEvent;
import com.ciaozn.alphatrader.common.event.RiskAlertEvent;
import com.ciaozn.alphatrader.common.model.Interval;
import com.ciaozn.alphatrader.common.model.Symbol;
import com.ciaozn.alphatrader.common.time.SystemClock;
import com.ciaozn.alphatrader.engine.EventEngine;
import com.ciaozn.alphatrader.gateway.AccountSnapshot;
import com.ciaozn.alphatrader.gateway.ExchangeGateway;
import com.ciaozn.alphatrader.gateway.ExchangeUnreachableException;
import com.ciaozn.alphatrader.gateway.GatewayConfig;
import com.ciaozn.alphatrader.gateway.OpenOrder;
import com.ciaozn.alphatrader.gateway.OrderAck;
import com.ciaozn.alphatrader.gateway.Position;
import com.ciaozn.alphatrader.gateway.TimeSync;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Binance USDⓈ-M perpetual gateway. P1 scope: market data (public kline stream).
 * Trading methods are wired in P3 - calling them now fails fast and loud.
 *
 * <p>Resilience model (FR-GW-02): a supervisor thread owns the connection lifecycle.
 * Any close triggers exponential-backoff reconnect; subscriptions live in the combined
 * stream URL, so resubscription is automatic on reconnect.
 */
public final class BinanceFuturesGateway implements ExchangeGateway {

    private static final Logger log = LoggerFactory.getLogger(BinanceFuturesGateway.class);

    public static final String LIVE_WS_BASE = "wss://fstream.binance.com/stream";
    public static final String TESTNET_WS_BASE = "wss://stream.binancefuture.com/stream";
    /** Private stream endpoint: /ws/&lt;listenKey&gt;, unlike the combined public /stream. */
    public static final String LIVE_WS_HOST = "wss://fstream.binance.com/ws";
    public static final String TESTNET_WS_HOST = "wss://stream.binancefuture.com/ws";

    /**
     * <p>Trading (T316/T317) is layered on the same object: a signed REST client for placing and
     * querying, and a private user data stream for what the exchange then says about those orders.
     * Both exist only when {@code connect} was given credentials - a market-data-only connection
     * stays unable to trade, and says so by throwing rather than by quietly doing nothing.
     */
    private final EventEngine engine;
    private final List<KlineSubscription> subscriptions = new CopyOnWriteArrayList<>();
    private final BackoffPolicy backoff = new BackoffPolicy();
    private volatile boolean running;
    private WebSocketClient wsClient;
    private Thread supervisor;

    private volatile GatewayConfig config;
    private volatile BinanceRestClient restClient;
    private volatile BinanceUserDataStream userData;
    private volatile OkHttpClient http;

    private record KlineSubscription(Symbol symbol, Interval interval) {
    }

    public BinanceFuturesGateway(EventEngine engine) {
        this.engine = engine;
    }

    @Override
    public void subscribeKline(Symbol symbol, Interval interval) {
        subscriptions.add(new KlineSubscription(symbol, interval));
    }

    @Override
    public void connect(GatewayConfig config) {
        if (subscriptions.isEmpty()) {
            throw new IllegalStateException("No subscriptions - call subscribeKline before connect");
        }
        String base = config.testnet() ? TESTNET_WS_BASE : LIVE_WS_BASE;
        String streams = subscriptions.stream()
                .map(sub -> sub.symbol().binance().toLowerCase() + "@kline_" + sub.interval().binanceCode())
                .collect(Collectors.joining("/"));
        URI uri = URI.create(base + "?streams=" + streams);
        running = true;
        this.config = config;
        startSupervisor(uri);
        log.info("Binance gateway connecting (testnet={}): {}", config.testnet(), uri);
        if (config.hasCredentials()) {
            startTrading(config);
        }
    }

    /**
     * Trading side of the connection: calibrate the clock, then open the private stream.
     *
     * <p>Order matters. The offset is measured before the first order can be signed, because a wrong
     * timestamp is rejected by the exchange in a way that is indistinguishable from a genuine
     * refusal - and the drift guard suspends orders precisely so that never has to be disambiguated.
     */
    private void startTrading(GatewayConfig config) {
        BinanceRestClient.Settings settings = (config.testnet()
                ? BinanceRestClient.Settings.TESTNET
                : BinanceRestClient.Settings.LIVE).withCredentials(config.apiKey(), config.apiSecret());
        http = new OkHttpClient();
        OkHttpBinanceTransport transport = new OkHttpBinanceTransport(http);
        String base = settings.baseUrl();
        TimeSync sync = new TimeSync(() -> serverTime(transport, base), new SystemClock());
        restClient = new BinanceRestClient(transport, settings, new SystemClock(), sync);
        long offset = restClient.calibrateOffset();
        if (restClient.clockDriftExceeded()) {
            // Orders are suspended from here on (the client refuses them); say so loudly, because a
            // process that is up but not trading looks identical to one with no signals.
            engine.publish(RiskAlertEvent.of(BinanceRestClient.RULE_CLOCK_DRIFT,
                    RiskAlertEvent.Severity.CRITICAL,
                    "local clock is " + offset + " ms from the exchange, orders suspended",
                    System.currentTimeMillis()));
        }
        userData = new BinanceUserDataStream(transport, base, config.testnet() ? TESTNET_WS_HOST : LIVE_WS_HOST,
                Map.of("X-MBX-APIKEY", config.apiKey()), engine::publish);
        userData.start();
    }

    private static long serverTime(BinanceTransport transport, String baseUrl) {
        String body = transport.call("GET", baseUrl + "/fapi/v1/time", Map.of());
        try {
            return new ObjectMapper().readTree(body).path("serverTime").asLong();
        } catch (IOException e) {
            throw new ExchangeUnreachableException("Cannot read exchange time", e);
        }
    }

    private void startSupervisor(URI uri) {
        supervisor = new Thread(() -> {
            while (running) {
                try {
                    connectOnce(uri);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception e) {
                    log.warn("Binance WS connection failed", e);
                }
                if (running) {
                    long delay = backoff.nextDelayMillis();
                    log.info("Reconnecting Binance WS in {} ms", delay);
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }, "binance-ws-supervisor");
        supervisor.setDaemon(true);
        supervisor.start();
    }

    /** Blocks until the connection dies; returns normally on clean close. */
    private void connectOnce(URI uri) throws InterruptedException {
        CountDownLatch closed = new CountDownLatch(1);
        wsClient = new WebSocketClient(uri) {
            @Override
            public void onOpen(ServerHandshake handshake) {
                backoff.reset();
                log.info("Binance WS connected: {}", uri.getHost());
            }

            @Override
            public void onMessage(String message) {
                try {
                    engine.publish(BinanceKlineParser.parse(message));
                } catch (Exception e) {
                    // Malformed frames must not kill the stream (spec edge case 1).
                    log.warn("Dropping unparseable WS message: {}", e.getMessage());
                }
            }

            @Override
            public void onClose(int code, String reason, boolean remote) {
                log.warn("Binance WS closed: code={} reason={} remote={}", code, reason, remote);
                closed.countDown();
            }

            @Override
            public void onError(Exception ex) {
                log.error("Binance WS error", ex);
            }
        };
        wsClient.connectBlocking(10, TimeUnit.SECONDS);
        closed.await();
    }

    @Override
    public OrderAck placeOrder(OrderRequestEvent request) {
        return trading().placeOrder(request);
    }

    @Override
    public void cancelOrder(String clientOrderId) {
        trading().cancelOrder(clientOrderId);
    }

    @Override
    public AccountSnapshot queryAccount() {
        return trading().queryAccount();
    }

    @Override
    public List<Position> queryPositions() {
        return trading().queryPositions();
    }

    @Override
    public List<OpenOrder> queryOpenOrders() {
        return trading().queryOpenOrders();
    }

    /**
     * The signed client exists only when the connection was given credentials. Failing here is the
     * point: "we are connected but cannot trade" must be an error, not an empty answer - an empty
     * open-order list from a gateway that was never authenticated would be read by reconciliation as
     * "the exchange has no orders", and it would then cancel everything we think we placed.
     */
    private BinanceRestClient trading() {
        BinanceRestClient client = restClient;
        if (client == null) {
            throw new IllegalStateException(
                    "This gateway is connected without credentials (market data only): "
                            + "set BINANCE_API_KEY/BINANCE_API_SECRET and alpha.trading.enabled=true to trade");
        }
        return client;
    }

    @Override
    public void close() {
        running = false;
        if (userData != null) {
            userData.close();
            userData = null;
        }
        if (restClient != null) {
            restClient = null;
        }
        if (wsClient != null) {
            wsClient.close();
        }
        if (supervisor != null) {
            supervisor.interrupt();
        }
        if (http != null) {
            http.dispatcher().cancelAll();
        }
        log.info("Binance gateway closed");
    }
}
