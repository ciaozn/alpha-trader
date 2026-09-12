package com.ciaozn.alphatrader.gateway.okx;

import com.ciaozn.alphatrader.common.event.OrderRequestEvent;
import com.ciaozn.alphatrader.common.model.OrderType;
import com.ciaozn.alphatrader.common.time.Clock;
import com.ciaozn.alphatrader.gateway.AccountSnapshot;
import com.ciaozn.alphatrader.gateway.OpenOrder;
import com.ciaozn.alphatrader.gateway.OrderAck;
import com.ciaozn.alphatrader.gateway.Position;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Signed OKX v5 REST calls (T406). The counterpart of {@code BinanceRestClient}, and deliberately a
 * separate class rather than a shared abstraction: the two exchanges disagree on the signature
 * pre-image, on where credentials live (a passphrase header OKX has and Binance does not), and on the
 * shape of every response.
 *
 * <p><b>The clock offset is calibrated once and applied to every timestamp</b>, exactly as on the
 * Binance side, because OKX rejects requests whose timestamp has drifted. A machine a few seconds off
 * produces rejections that look like exchange errors but are ours.
 *
 * <p><b>Cancellation needs the instrument, and the interface does not carry it.</b>
 * {@code ExchangeGateway.cancelOrder} takes only the client order id, while OKX requires an
 * {@code instId} to cancel. The mapping is remembered here, from placement, because the alternative -
 * widening the interface for one exchange's requirement - would put OKX's shape into every caller.
 * A cancel for an id this instance never placed is refused rather than guessed.
 *
 * <p>Demo trading is a header ({@code x-simulated-trading: 1}), not a different host, so the demo
 * flag rides along with the credentials.
 */
public final class OkxRestClient {

    private static final Logger log = LoggerFactory.getLogger(OkxRestClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String ORDER_PATH = "/api/v5/trade/order";
    private static final String CANCEL_PATH = "/api/v5/trade/cancel-order";
    private static final String POSITIONS_PATH = "/api/v5/account/positions";
    private static final String BALANCE_PATH = "/api/v5/account/balance";
    private static final String RISK_PATH = "/api/v5/account/account-position-risk";
    private static final String PENDING_PATH = "/api/v5/trade/orders-pending";
    private static final String TIME_PATH = "/api/v5/public/time";

    public static final String LIVE_BASE_URL = "https://www.okx.com";
    /** OKX demo trading uses the production host with a header, so the base URL is the same. */
    public static final String DEMO_BASE_URL = "https://www.okx.com";

    public record Settings(String baseUrl, String apiKey, String apiSecret, String passphrase,
                           boolean simulated) {

        public static final Settings LIVE = new Settings(LIVE_BASE_URL, null, null, null, false);
        public static final Settings DEMO = new Settings(DEMO_BASE_URL, null, null, null, true);

        public Settings withCredentials(String apiKey, String apiSecret, String passphrase) {
            return new Settings(baseUrl, apiKey, apiSecret, passphrase, simulated);
        }

        public boolean hasCredentials() {
            return apiKey != null && !apiKey.isBlank()
                    && apiSecret != null && !apiSecret.isBlank()
                    && passphrase != null && !passphrase.isBlank();
        }
    }

    private final OkxTransport transport;
    private final Settings settings;
    private final Clock clock;
    private final Map<String, String> instrumentByClientOrderId = new HashMap<>();
    private long offsetMillis;

    public OkxRestClient(OkxTransport transport, Settings settings, Clock clock) {
        this.transport = transport;
        this.settings = settings;
        this.clock = clock;
    }

    /** Measures the exchange clock offset from the public time endpoint. Positive = exchange ahead. */
    public long calibrateOffset() {
        String body = transport.call("GET", settings.baseUrl() + TIME_PATH, Map.of(), null);
        try {
            long serverTime = MAPPER.readTree(body).path("data").path(0).path("ts").asLong();
            this.offsetMillis = serverTime - clock.nowMillis();
            log.info("OKX clock offset calibrated: {} ms", offsetMillis);
            return offsetMillis;
        } catch (IOException e) {
            throw new UncheckedIOException("Malformed OKX time response", e);
        }
    }

    public OrderAck placeOrder(OrderRequestEvent request) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("instId", request.symbol().okx());
        // Cross margin, matching the book: this system trades one net position per symbol, not
        // isolated margin per order.
        body.put("tdMode", "cross");
        body.put("side", request.side() == com.ciaozn.alphatrader.common.model.Side.BUY ? "buy" : "sell");
        body.put("ordType", request.orderType() == OrderType.MARKET ? "market" : "limit");
        body.put("sz", request.qty().toPlainString());
        if (request.orderType() == OrderType.LIMIT) {
            if (request.price() == null) {
                throw new IllegalArgumentException("LIMIT order without a price: " + request.clientOrderId());
            }
            body.put("px", request.price().toPlainString());
        }
        body.put("clOrdId", request.clientOrderId());

        String response = transport.call("POST", settings.baseUrl() + ORDER_PATH,
                headers("POST", ORDER_PATH, body.toString()), body.toString());
        OrderAck ack = OkxOrderAckParser.parse(response, request.clientOrderId());
        if (ack.accepted()) {
            // Remembered so cancelOrder can name the instrument OKX needs (see class javadoc).
            instrumentByClientOrderId.put(request.clientOrderId(), request.symbol().okx());
        }
        return ack;
    }

    public void cancelOrder(String clientOrderId) {
        String instId = instrumentByClientOrderId.get(clientOrderId);
        if (instId == null) {
            throw new IllegalStateException("Cannot cancel " + clientOrderId
                    + ": OKX needs the instrument and this client never placed that order");
        }
        ObjectNode body = MAPPER.createObjectNode();
        body.put("instId", instId);
        body.put("clOrdId", clientOrderId);
        transport.call("POST", settings.baseUrl() + CANCEL_PATH,
                headers("POST", CANCEL_PATH, body.toString()), body.toString());
    }

    public AccountSnapshot queryAccount() {
        String balance = get(BALANCE_PATH);
        String risk = get(RISK_PATH);
        return OkxAccountParser.parse(balance, risk);
    }

    public List<Position> queryPositions() {
        return OkxPositionParser.parse(get(POSITIONS_PATH + "?instType=SWAP"));
    }

    public List<OpenOrder> queryOpenOrders() {
        return OkxOpenOrdersParser.parse(get(PENDING_PATH + "?instType=SWAP"));
    }

    /**
     * The signed path and the requested URL are the same string - including the query - which is the
     * whole reason this helper exists: building them separately is how a signature ends up covering
     * something other than what was sent.
     */
    private String get(String pathWithQuery) {
        return transport.call("GET", settings.baseUrl() + pathWithQuery,
                headers("GET", pathWithQuery, ""), null);
    }

    /**
     * Signs {@code requestPath} as given, query string included: OKX's pre-image is
     * {@code timestamp + method + requestPath + body}, and its own example signs
     * {@code /api/v5/account/balance?ccy=BTC}. A body, when present, is signed as the exact JSON that
     * is sent - re-serialising it would change the bytes the signature covers (error 50113).
     */
    private Map<String, String> headers(String method, String requestPath, String body) {
        if (!settings.hasCredentials()) {
            throw new IllegalStateException("No OKX credentials: set OKX_API_KEY / OKX_API_SECRET / OKX_PASSPHRASE");
        }
        String timestamp = OkxSigner.timestamp(clock.nowMillis() + offsetMillis);
        Map<String, String> headers = new HashMap<>();
        headers.put("OK-ACCESS-KEY", settings.apiKey());
        headers.put("OK-ACCESS-SIGN", OkxSigner.sign(timestamp, method, requestPath, body, settings.apiSecret()));
        headers.put("OK-ACCESS-TIMESTAMP", timestamp);
        headers.put("OK-ACCESS-PASSPHRASE", settings.passphrase());
        headers.put("Content-Type", "application/json");
        if (settings.simulated()) {
            headers.put("x-simulated-trading", "1");
        }
        return headers;
    }

    private String withQuery(String path, String query) {
        return settings.baseUrl() + path + (query.equals("?") ? "" : query);
    }
}
