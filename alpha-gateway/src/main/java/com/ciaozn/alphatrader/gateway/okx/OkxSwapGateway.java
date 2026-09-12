package com.ciaozn.alphatrader.gateway.okx;

import com.ciaozn.alphatrader.common.event.OrderRequestEvent;
import com.ciaozn.alphatrader.common.model.Interval;
import com.ciaozn.alphatrader.common.model.Symbol;
import com.ciaozn.alphatrader.common.time.SystemClock;
import com.ciaozn.alphatrader.engine.EventEngine;
import com.ciaozn.alphatrader.gateway.AccountSnapshot;
import com.ciaozn.alphatrader.gateway.BackoffPolicy;
import com.ciaozn.alphatrader.gateway.ExchangeGateway;
import com.ciaozn.alphatrader.gateway.GatewayConfig;
import com.ciaozn.alphatrader.gateway.OpenOrder;
import com.ciaozn.alphatrader.gateway.OrderAck;
import com.ciaozn.alphatrader.gateway.Position;
import okhttp3.OkHttpClient;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * OKX perpetual-swap gateway (T406, FR-GW-06): public candle stream plus signed v5 REST trading.
 *
 * <p>Mirrors the Binance gateway's shape - a supervisor thread owns the connection, subscriptions
 * live in a list, reconnect uses exponential backoff, and everything learned is published as an
 * event - because the assembly above must not care which exchange is attached. What differs is where
 * it differs at the exchange: candles arrive on the {@code business} endpoint, the subscription is a
 * JSON message rather than a URL parameter (so resubscribing after a reconnect is an explicit step
 * here, not free), and demo trading is a header rather than a host.
 *
 * <p>Order and execution facts come from REST polling via reconciliation, not from a private stream:
 * OKX's private order channel is a further subscription whose lifecycle (login frames, per-account
 * subscription limits) is its own piece of work, and reconciliation already queries the exchange
 * every 60 seconds. That is a deliberate, documented limit of this implementation, not an oversight -
 * fills are therefore seen up to one reconciliation period late on OKX, which is acceptable for
 * k-line strategies and not acceptable for anything faster.
 */
public final class OkxSwapGateway implements ExchangeGateway {

    private static final Logger log = LoggerFactory.getLogger(OkxSwapGateway.class);

    /** Public candle pushes live on the business endpoint in v5. */
    public static final String LIVE_WS = "wss://ws.okx.com:8443/ws/v5/business";
    /** Demo trading has its own websocket host (unlike REST, which only differs by header). */
    public static final String DEMO_WS = "wss://wspap.okx.com:8443/ws/v5/business";

    private final EventEngine engine;
    private final List<Subscription> subscriptions = new CopyOnWriteArrayList<>();
    private final BackoffPolicy backoff = new BackoffPolicy();

    private volatile boolean running;
    private volatile OkxRestClient restClient;
    private volatile OkHttpClient http;
    private WebSocketClient socket;
    private Thread supervisor;

    private record Subscription(Symbol symbol, Interval interval) {
        String channel() {
            return switch (interval) {
                case M1 -> "candle1m";
                case M5 -> "candle5m";
                case M15 -> "candle15m";
                case H1 -> "candle1H";
                case H4 -> "candle4H";
                case D1 -> "candle1D";
            };
        }
    }

    public OkxSwapGateway(EventEngine engine) {
        this.engine = engine;
    }

    @Override
    public void subscribeKline(Symbol symbol, Interval interval) {
        subscriptions.add(new Subscription(symbol, interval));
    }

    @Override
    public void connect(GatewayConfig config) {
        if (subscriptions.isEmpty()) {
            throw new IllegalStateException("No subscriptions - call subscribeKline before connect");
        }
        running = true;
        if (config.hasCredentials()) {
            http = new OkHttpClient();
            OkxRestClient.Settings settings = (config.testnet() ? OkxRestClient.Settings.DEMO
                    : OkxRestClient.Settings.LIVE)
                    .withCredentials(config.apiKey(), config.apiSecret(), config.passphrase());
            restClient = new OkxRestClient(new OkHttpOkxTransport(http), settings, new SystemClock());
            restClient.calibrateOffset();
        }
        String wsBase = config.testnet() ? DEMO_WS : LIVE_WS;
        supervisor = new Thread(() -> supervise(wsBase), "okx-ws-supervisor");
        supervisor.setDaemon(true);
        supervisor.start();
        log.info("OKX gateway connecting (demo={}): {}", config.testnet(), wsBase);
    }

    private void supervise(String wsBase) {
        while (running) {
            try {
                connectOnce(wsBase);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.warn("OKX WS connection failed", e);
            }
            if (running) {
                long delay = backoff.nextDelayMillis();
                log.info("Reconnecting OKX WS in {} ms", delay);
                sleep(delay);
            }
        }
    }

    private void connectOnce(String wsBase) throws InterruptedException {
        CountDownLatch closed = new CountDownLatch(1);
        socket = new WebSocketClient(URI.create(wsBase)) {
            @Override
            public void onOpen(ServerHandshake handshake) {
                backoff.reset();
                // Explicit resubscribe: OKX takes the channel list in a message, so a reconnect
                // starts from nothing and the subscriptions have to be re-sent here.
                send(subscribeMessage());
                log.info("OKX WS connected to {}", wsBase);
            }

            @Override
            public void onMessage(String message) {
                try {
                    // One frame per subscription, so the interval is not in the payload: try each
                    // subscribed interval and take the parse that matches.
                    for (Subscription subscription : subscriptions) {
                        var event = OkxKlineParser.parse(message, subscription.interval());
                        if (event.isPresent() && event.get().symbol().equals(subscription.symbol())) {
                            engine.publish(event.get());
                            return;
                        }
                    }
                } catch (RuntimeException e) {
                    log.warn("Dropping unparseable OKX WS frame: {}", e.getMessage());
                }
            }

            @Override
            public void onClose(int code, String reason, boolean remote) {
                log.warn("OKX WS closed: code={} reason={} remote={}", code, reason, remote);
                closed.countDown();
            }

            @Override
            public void onError(Exception ex) {
                log.error("OKX WS error", ex);
            }
        };
        socket.connectBlocking(10, TimeUnit.SECONDS);
        closed.await();
    }

    private String subscribeMessage() {
        StringBuilder args = new StringBuilder();
        for (Subscription subscription : subscriptions) {
            if (args.length() > 0) {
                args.append(',');
            }
            args.append("{\"channel\":\"").append(subscription.channel())
                    .append("\",\"instId\":\"").append(subscription.symbol().okx()).append("\"}");
        }
        return "{\"op\":\"subscribe\",\"args\":[" + args + "]}";
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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

    private OkxRestClient trading() {
        OkxRestClient client = restClient;
        if (client == null) {
            throw new IllegalStateException("OKX gateway connected without credentials (market data only):"
                    + " set OKX_API_KEY / OKX_API_SECRET / OKX_PASSPHRASE to trade");
        }
        return client;
    }

    @Override
    public void close() {
        running = false;
        restClient = null;
        if (socket != null) {
            socket.close();
        }
        if (supervisor != null) {
            supervisor.interrupt();
        }
        if (http != null) {
            http.dispatcher().cancelAll();
        }
        log.info("OKX gateway closed");
    }
}
