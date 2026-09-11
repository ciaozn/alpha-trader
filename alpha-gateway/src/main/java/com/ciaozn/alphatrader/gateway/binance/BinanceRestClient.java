package com.ciaozn.alphatrader.gateway.binance;

import com.ciaozn.alphatrader.common.event.OrderRequestEvent;
import com.ciaozn.alphatrader.common.model.OrderType;
import com.ciaozn.alphatrader.common.time.Clock;
import com.ciaozn.alphatrader.gateway.AccountSnapshot;
import com.ciaozn.alphatrader.gateway.ExchangeUnreachableException;
import com.ciaozn.alphatrader.gateway.OpenOrder;
import com.ciaozn.alphatrader.gateway.OrderAck;
import com.ciaozn.alphatrader.gateway.Position;
import com.ciaozn.alphatrader.gateway.TimeSync;
import okhttp3.HttpUrl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Signed Binance USDⓈ-M REST calls (T316). Knows about signing, timestamps and the clock; knows
 * nothing about the event loop, the OMS or what an order means.
 *
 * <p><b>Every call is "signed with the offset applied".</b> The timestamp is
 * {@code clock.nowMillis() + offset}, where the offset came from {@link TimeSync} at connect time.
 * Binance rejects requests whose timestamp is outside its receive window, and a machine whose clock
 * is a few seconds off produces rejections that look like exchange errors but are ours.
 *
 * <p><b>Clock drift suspends trading rather than risking bad timestamps</b> (spec edge case 8):
 * {@link #placeOrder} refuses with reason {@link #RULE_CLOCK_DRIFT} instead of sending a request
 * that would be rejected - and, worse, would be indistinguishable from a genuine refusal once it
 * came back. Queries keep working, so reconciliation can still see the account.
 *
 * <p><b>Refused and unreachable stay different all the way up.</b> Business errors become
 * {@code OrderAck.rejected}; transport failures and 5xx/429 leave this class as
 * {@link ExchangeUnreachableException}, which the order sender translates into "outcome unknown"
 * rather than "order rejected".
 */
public final class BinanceRestClient {

    /** Reason string used when orders are suspended for clock drift (spec edge case 8). */
    public static final String RULE_CLOCK_DRIFT = "GW-clock-drift";

    private static final Logger log = LoggerFactory.getLogger(BinanceRestClient.class);
    private static final String ORDER_PATH = "fapi/v1/order";
    private static final String ACCOUNT_PATH = "fapi/v2/account";
    private static final String POSITION_PATH = "fapi/v2/positionRisk";
    private static final String OPEN_ORDERS_PATH = "fapi/v1/openOrders";

    /**
     * @param baseUrl      REST base, testnet or live - the only difference between them (FR-GW-05)
     * @param recvWindowMs receive window sent with every signed request; 5000 is Binance's default
     *                     and long enough for one retry, short enough not to widen the replay window
     */
    public record Settings(String baseUrl, String apiKey, String apiSecret, long recvWindowMs) {

        public static final Settings TESTNET =
                new Settings(BinanceTradingRulesFetcher.TESTNET_BASE_URL, null, null, 5000L);
        public static final Settings LIVE =
                new Settings(BinanceTradingRulesFetcher.LIVE_BASE_URL, null, null, 5000L);

        public Settings withCredentials(String apiKey, String apiSecret) {
            return new Settings(baseUrl, apiKey, apiSecret, recvWindowMs);
        }

        public boolean hasCredentials() {
            return apiKey != null && !apiKey.isBlank() && apiSecret != null && !apiSecret.isBlank();
        }
    }

    private final BinanceTransport transport;
    private final Settings settings;
    private final Clock clock;
    private final TimeSync sync;
    private long offsetMillis;

    public BinanceRestClient(BinanceTransport transport, Settings settings, Clock clock, TimeSync sync) {
        if (transport == null || settings == null || clock == null || sync == null) {
            throw new IllegalArgumentException("transport, settings, clock and time sync are required");
        }
        this.transport = transport;
        this.settings = settings;
        this.clock = clock;
        this.sync = sync;
    }

    /** Measures the exchange clock offset once, at connect time. Positive = exchange ahead. */
    public long calibrateOffset() {
        this.offsetMillis = sync.calibrateOffset();
        log.info("Exchange clock offset calibrated: {} ms", offsetMillis);
        return offsetMillis;
    }

    public long offsetMillis() {
        return offsetMillis;
    }

    public boolean clockDriftExceeded() {
        return sync.isDriftExceeded(offsetMillis);
    }

    public OrderAck placeOrder(OrderRequestEvent request) {
        if (clockDriftExceeded()) {
            String reason = RULE_CLOCK_DRIFT + ": local clock is " + offsetMillis
                    + " ms from the exchange, orders suspended (spec edge case 8)";
            log.error("Order {} not sent - {}", request.clientOrderId(), reason);
            return OrderAck.rejected(request.clientOrderId(), reason);
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put("symbol", request.symbol().binance());
        params.put("side", request.side().name());
        params.put("type", request.orderType().name());
        params.put("quantity", request.qty().toPlainString());
        if (request.orderType() == OrderType.LIMIT) {
            if (request.price() == null) {
                throw new IllegalArgumentException("LIMIT order without a price: " + request.clientOrderId());
            }
            params.put("price", request.price().toPlainString());
            params.put("timeInForce", "GTC");
        }
        // The idempotency key: a retry of this order carries the same id, so the exchange can
        // recognise a duplicate instead of filling it twice (FR-EX-02).
        params.put("newClientOrderId", request.clientOrderId());
        String body = transport.call("POST", signed(ORDER_PATH, params), headers());
        return BinanceOrderAckParser.parse(body, request.clientOrderId());
    }

    public void cancelOrder(String clientOrderId) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("origClientOrderId", clientOrderId);
        transport.call("DELETE", signed(ORDER_PATH, params), headers());
    }

    public AccountSnapshot queryAccount() {
        return BinanceAccountParser.parse(transport.call("GET", signed(ACCOUNT_PATH, Map.of()), headers()));
    }

    public List<Position> queryPositions() {
        return BinancePositionParser.parse(transport.call("GET", signed(POSITION_PATH, Map.of()), headers()));
    }

    public List<OpenOrder> queryOpenOrders() {
        return BinanceOpenOrdersParser.parse(transport.call("GET", signed(OPEN_ORDERS_PATH, Map.of()), headers()));
    }

    private Map<String, String> headers() {
        return Map.of("X-MBX-APIKEY", settings.apiKey() == null ? "" : settings.apiKey());
    }

    /**
     * Builds the URL and signs exactly the query string that will be sent - including the timestamp,
     * which is why this method takes the clock rather than a caller-supplied time.
     */
    private String signed(String path, Map<String, String> params) {
        if (!settings.hasCredentials()) {
            throw new IllegalStateException("No API credentials: trading calls need BINANCE_API_KEY/SECRET");
        }
        HttpUrl.Builder builder = HttpUrl.get(settings.baseUrl()).newBuilder().addPathSegments(path);
        params.forEach(builder::addQueryParameter);
        builder.addQueryParameter("recvWindow", Long.toString(settings.recvWindowMs()));
        builder.addQueryParameter("timestamp", Long.toString(clock.nowMillis() + offsetMillis));
        HttpUrl url = builder.build();
        String query = url.query();
        return url + "&signature=" + BinanceSigner.sign(query, settings.apiSecret());
    }
}
