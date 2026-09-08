package com.ciaozn.alphatrader.gateway.binance;

import com.ciaozn.alphatrader.common.event.OrderRequestEvent;
import com.ciaozn.alphatrader.common.model.Interval;
import com.ciaozn.alphatrader.common.model.Symbol;
import com.ciaozn.alphatrader.engine.EventEngine;
import com.ciaozn.alphatrader.gateway.AccountSnapshot;
import com.ciaozn.alphatrader.gateway.ExchangeGateway;
import com.ciaozn.alphatrader.gateway.GatewayConfig;
import com.ciaozn.alphatrader.gateway.OpenOrder;
import com.ciaozn.alphatrader.gateway.OrderAck;
import com.ciaozn.alphatrader.gateway.Position;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.List;
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

    private final EventEngine engine;
    private final List<KlineSubscription> subscriptions = new CopyOnWriteArrayList<>();
    private final BackoffPolicy backoff = new BackoffPolicy();
    private volatile boolean running;
    private WebSocketClient wsClient;
    private Thread supervisor;

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
        startSupervisor(uri);
        log.info("Binance gateway connecting (testnet={}): {}", config.testnet(), uri);
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
        throw new UnsupportedOperationException("Trading is implemented in P3 (order REST + user data stream)");
    }

    @Override
    public void cancelOrder(String clientOrderId) {
        throw new UnsupportedOperationException("Trading is implemented in P3");
    }

    @Override
    public AccountSnapshot queryAccount() {
        throw new UnsupportedOperationException("Account queries are implemented in P3");
    }

    @Override
    public List<Position> queryPositions() {
        throw new UnsupportedOperationException("Position queries are implemented in P3");
    }

    @Override
    public List<OpenOrder> queryOpenOrders() {
        throw new UnsupportedOperationException("Open-order queries are implemented in P3");
    }

    @Override
    public void close() {
        running = false;
        if (wsClient != null) {
            wsClient.close();
        }
        if (supervisor != null) {
            supervisor.interrupt();
        }
        log.info("Binance gateway closed");
    }
}
