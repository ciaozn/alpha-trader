package com.ciaozn.alphatrader.gateway.binance;

import com.ciaozn.alphatrader.common.event.OrderRequestEvent;
import com.ciaozn.alphatrader.common.model.OrderType;
import com.ciaozn.alphatrader.common.model.Side;
import com.ciaozn.alphatrader.common.model.Symbol;
import com.ciaozn.alphatrader.common.time.VirtualClock;
import com.ciaozn.alphatrader.gateway.AccountSnapshot;
import com.ciaozn.alphatrader.gateway.ExchangeUnreachableException;
import com.ciaozn.alphatrader.gateway.OpenOrder;
import com.ciaozn.alphatrader.gateway.OrderAck;
import com.ciaozn.alphatrader.gateway.Position;
import com.ciaozn.alphatrader.gateway.TimeSync;
import okhttp3.HttpUrl;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BinanceRestClientTest {

    private static final String SECRET = "s3cr3t";
    private static final Symbol BTC = Symbol.parse("BTCUSDT.PERP");

    /** Records every call and answers with queued bodies - the seam that makes REST testable offline. */
    private static final class StubTransport implements BinanceTransport {
        final List<String> methods = new ArrayList<>();
        final List<String> urls = new ArrayList<>();
        final List<Map<String, String>> headers = new ArrayList<>();
        String body = "{}";
        RuntimeException failure;

        @Override
        public String call(String method, String url, Map<String, String> headers) {
            if (failure != null) {
                throw failure;
            }
            this.methods.add(method);
            this.urls.add(url);
            this.headers.add(headers);
            return body;
        }

        @Override
        public void close() {
        }
    }

    private final StubTransport transport = new StubTransport();
    private final VirtualClock clock = new VirtualClock(1_700_000_000_000L);

    private BinanceRestClient client() {
        return client(BinanceRestClient.Settings.TESTNET.withCredentials("key", SECRET),
                new TimeSync(() -> clock.nowMillis(), clock));
    }

    private BinanceRestClient client(BinanceRestClient.Settings settings, TimeSync sync) {
        return new BinanceRestClient(transport, settings, clock, sync);
    }

    private OrderRequestEvent marketOrder() {
        return OrderRequestEvent.of("ma-1-1", BTC, Side.BUY, OrderType.MARKET,
                new BigDecimal("0.500"), null, clock.nowMillis());
    }

    @Test
    void signatureMatchesAnIndependentlyComputedHmac() {
        transport.body = "{\"orderId\":9283484,\"status\":\"NEW\"}";
        BinanceRestClient client = client();
        client.calibrateOffset();

        OrderAck ack = client.placeOrder(marketOrder());

        assertThat(ack.accepted()).isTrue();
        assertThat(ack.exchangeOrderId()).isEqualTo("9283484");

        HttpUrl url = HttpUrl.get(transport.urls.get(0));
        String signed = url.queryParameter("signature");
        String query = stripSignature(transport.urls.get(0));
        // Re-derived here from javax.crypto, NOT by calling BinanceSigner: an HMAC compared with
        // itself proves nothing about what the exchange will see.
        assertThat(signed).isEqualTo(hmacSha256Hex(query, SECRET));
    }

    /** Independent HMAC-SHA256, written against the JDK rather than against the class under test. */
    private static String hmacSha256Hex(String payload, String secret) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(
                    secret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(raw.length * 2);
            for (byte b : raw) {
                out.append(String.format("%02x", b));
            }
            return out.toString();
        } catch (Exception e) {
            throw new AssertionError("HmacSHA256 unavailable", e);
        }
    }

    @Test
    void signedRequestCarriesTimestampKeyAndIdempotencyKey() {
        transport.body = "{\"orderId\":1}";
        BinanceRestClient client = client();
        client.calibrateOffset();
        client.placeOrder(marketOrder());

        HttpUrl url = HttpUrl.get(transport.urls.get(0));
        assertThat(transport.methods.get(0)).isEqualTo("POST");
        assertThat(url.encodedPath()).isEqualTo("/fapi/v1/order");
        assertThat(url.queryParameter("timestamp")).isEqualTo(Long.toString(clock.nowMillis()));
        assertThat(url.queryParameter("newClientOrderId")).isEqualTo("ma-1-1");
        assertThat(url.queryParameter("quantity")).isEqualTo("0.500");
        assertThat(url.queryParameter("type")).isEqualTo("MARKET");
        assertThat(transport.headers.get(0)).containsEntry("X-MBX-APIKEY", "key");
    }

    @Test
    void limitOrderSendsPriceAndTimeInForce() {
        transport.body = "{\"orderId\":2}";
        BinanceRestClient client = client();
        client.calibrateOffset();
        client.placeOrder(OrderRequestEvent.of("ma-1-2", BTC, Side.SELL, OrderType.LIMIT,
                new BigDecimal("0.25"), new BigDecimal("70000.5"), clock.nowMillis()));

        HttpUrl url = HttpUrl.get(transport.urls.get(0));
        assertThat(url.queryParameter("price")).isEqualTo("70000.5");
        assertThat(url.queryParameter("timeInForce")).isEqualTo("GTC");
    }

    @Test
    void businessRefusalIsArejectedAckNotAnException() {
        transport.body = "{\"code\":-2010,\"msg\":\"Account has insufficient balance for requested action.\"}";
        BinanceRestClient client = client();
        client.calibrateOffset();

        OrderAck ack = client.placeOrder(marketOrder());

        assertThat(ack.accepted()).isFalse();
        assertThat(ack.rejectReason()).contains("-2010").contains("insufficient balance");
    }

    @Test
    void unreachableExchangePropagatesSoTheOutcomeStaysUnknown() {
        transport.failure = new ExchangeUnreachableException("boom");
        BinanceRestClient client = client();
        client.calibrateOffset();

        assertThatThrownBy(() -> client.placeOrder(marketOrder()))
                .isInstanceOf(ExchangeUnreachableException.class);
    }

    @Test
    void clockDriftSuspendsOrdersWithoutTouchingTheNetwork() {
        // Exchange clock 5s ahead of local: beyond the 1s threshold, so orders must not be sent.
        TimeSync drifted = new TimeSync(() -> clock.nowMillis() + 5_000L, clock);
        BinanceRestClient client = client(BinanceRestClient.Settings.TESTNET.withCredentials("key", SECRET),
                drifted);
        client.calibrateOffset();

        assertThat(client.clockDriftExceeded()).isTrue();
        OrderAck ack = client.placeOrder(marketOrder());

        assertThat(ack.accepted()).isFalse();
        assertThat(ack.rejectReason()).contains(BinanceRestClient.RULE_CLOCK_DRIFT);
        assertThat(transport.urls).isEmpty();
    }

    @Test
    void queriesParseIntoGatewayTypes() {
        transport.body = "{\"totalMarginBalance\":\"1010.5\",\"availableBalance\":\"900.25\","
                + "\"totalMaintMargin\":\"20.2\"}";
        AccountSnapshot account = client().queryAccount();
        assertThat(account.totalEquity()).isEqualByComparingTo(new BigDecimal("1010.5"));
        assertThat(account.availableMargin()).isEqualByComparingTo(new BigDecimal("900.25"));
        assertThat(account.marginRatio()).isGreaterThan(BigDecimal.ZERO);

        transport.body = "[{\"symbol\":\"BTCUSDT\",\"positionAmt\":\"-0.500\",\"entryPrice\":\"50000\","
                + "\"unRealizedProfit\":\"-12.5\",\"markPrice\":\"51000\"},"
                + "{\"symbol\":\"ETHUSDT\",\"positionAmt\":\"0\",\"entryPrice\":\"0\"}]";
        List<Position> positions = client().queryPositions();
        assertThat(positions).hasSize(1);
        assertThat(positions.get(0).symbol().unified()).isEqualTo("BTCUSDT.PERP");
        assertThat(positions.get(0).direction()).isEqualTo(com.ciaozn.alphatrader.common.model.Direction.SHORT);
        assertThat(positions.get(0).qty()).isEqualByComparingTo(new BigDecimal("0.500"));

        transport.body = "[{\"symbol\":\"BTCUSDT\",\"orderId\":9,\"clientOrderId\":\"ma-1-9\",\"side\":\"BUY\","
                + "\"origQty\":\"0.5\",\"price\":\"0\",\"status\":\"NEW\"},"
                + "{\"symbol\":\"BTCUSDT\",\"clientOrderId\":\"old\",\"side\":\"SELL\",\"origQty\":\"1\","
                + "\"price\":\"0\",\"status\":\"FILLED\"}]";
        List<OpenOrder> open = client().queryOpenOrders();
        assertThat(open).hasSize(1);
        assertThat(open.get(0).clientOrderId()).isEqualTo("ma-1-9");
        assertThat(open.get(0).status()).isEqualTo(com.ciaozn.alphatrader.common.model.OrderStatus.SUBMITTED);
    }

    @Test
    void refusesToSignWithoutCredentials() {
        BinanceRestClient client = new BinanceRestClient(transport, BinanceRestClient.Settings.TESTNET,
                clock, new TimeSync(() -> clock.nowMillis(), clock));
        assertThatThrownBy(() -> client.queryAccount()).isInstanceOf(IllegalStateException.class);
    }

    private static String stripSignature(String url) {
        int start = url.indexOf('?');
        String query = url.substring(start + 1);
        int end = query.indexOf("&signature=");
        return end < 0 ? query : query.substring(0, end);
    }
}
