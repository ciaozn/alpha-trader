package com.ciaozn.alphatrader.gateway.binance;

import com.ciaozn.alphatrader.common.event.OrderReportEvent;
import com.ciaozn.alphatrader.common.model.OrderStatus;
import com.ciaozn.alphatrader.gateway.ExchangeUnreachableException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BinanceUserDataStreamTest {

    private static final String NEW_ORDER = """
            {"e":"ORDER_TRADE_UPDATE","E":1568879465651,"T":1568879465650,"o":{
              "s":"BTCUSDT","c":"ma-1-7","S":"BUY","o":"MARKET","f":"GTC","q":"0.500",
              "p":"0","X":"NEW","i":11737481,"l":"0","z":"0","L":"0","n":"0","N":"USDT",
              "T":1568879465650}}
            """;

    private static final String PARTIAL_FILL = """
            {"e":"ORDER_TRADE_UPDATE","E":1568879465999,"T":1568879465700,"o":{
              "s":"BTCUSDT","c":"ma-1-7","S":"BUY","o":"MARKET","f":"GTC","q":"0.500",
              "p":"0","X":"PARTIALLY_FILLED","i":11737481,"l":"0.200","z":"0.200",
              "L":"50000.10","n":"0.50000000","N":"USDT","T":1568879465700}}
            """;

    private static final String ACCOUNT_UPDATE = """
            {"e":"ACCOUNT_UPDATE","E":1568879465651,"a":{"B":[{"a":"USDT","wb":"1000"}]}}
            """;

    private static final class StubTransport implements BinanceTransport {
        final List<String> methods = new ArrayList<>();
        final List<String> urls = new ArrayList<>();
        String body = "{}";

        @Override
        public String call(String method, String url, Map<String, String> headers) {
            methods.add(method);
            urls.add(url);
            return body;
        }

        @Override
        public void close() {
        }
    }

    private final StubTransport transport = new StubTransport();
    private final List<OrderReportEvent> published = new ArrayList<>();

    private BinanceUserDataStream stream(Consumer<OrderReportEvent> sink) {
        return new BinanceUserDataStream(transport, "https://testnet.binancefuture.com",
                "wss://stream.binancefuture.com/ws", Map.of("X-MBX-APIKEY", "key"), sink);
    }

    @Test
    void newOrderBecomesALifecycleReport() {
        Optional<OrderReportEvent> report = BinanceUserDataParser.parse(NEW_ORDER);

        assertThat(report).isPresent();
        assertThat(report.get().clientOrderId()).isEqualTo("ma-1-7");
        assertThat(report.get().exchangeOrderId()).isEqualTo("11737481");
        assertThat(report.get().status()).isEqualTo(OrderStatus.SUBMITTED);
        assertThat(report.get().isTrade()).isFalse();
        assertThat(report.get().timestamp()).isEqualTo(1568879465650L);
    }

    @Test
    void executionBecomesATradeReportCarryingNoStatus() {
        Optional<OrderReportEvent> report = BinanceUserDataParser.parse(PARTIAL_FILL);

        assertThat(report).isPresent();
        assertThat(report.get().isTrade()).isTrue();
        assertThat(report.get().lastQty()).isEqualByComparingTo(new BigDecimal("0.200"));
        assertThat(report.get().lastPrice()).isEqualByComparingTo(new BigDecimal("50000.10"));
        assertThat(report.get().fee()).isEqualByComparingTo(new BigDecimal("0.5"));
        // The status is the OMS's to derive from quantities, not the stream's to assert.
        assertThat(report.get().status()).isNull();
    }

    @Test
    void nonOrderFramesAreIgnored() {
        assertThat(BinanceUserDataParser.parse(ACCOUNT_UPDATE)).isEmpty();
    }

    @Test
    void expiredStatusIsCancelled() {
        String payload = NEW_ORDER.replace("\"X\":\"NEW\"", "\"X\":\"EXPIRED\"");
        assertThat(BinanceUserDataParser.parse(payload).orElseThrow().status())
                .isEqualTo(OrderStatus.CANCELED);
    }

    @Test
    void listenKeyIsCreatedAndTornDownThroughTheTransport() {
        transport.body = "{\"listenKey\":\"abc123\"}";
        BinanceUserDataStream stream = stream(published::add);

        assertThat(stream.createListenKey()).isEqualTo("abc123");
        stream.keepAlive();
        stream.close();

        assertThat(transport.methods).containsExactly("POST", "PUT", "DELETE");
        assertThat(transport.urls).allMatch(url -> url.endsWith("/fapi/v1/listenKey"));
    }

    @Test
    void keepaliveBeforeTheFirstKeyIsANoOpRatherThanAnError() {
        BinanceUserDataStream stream = stream(published::add);
        stream.keepAlive();
        assertThat(transport.methods).isEmpty();
    }

    @Test
    void anUnreachableExchangeDuringKeyCreationIsNotSwallowed() {
        transport.body = "{}";
        BinanceUserDataStream stream = stream(published::add);
        assertThatThrownBy(stream::createListenKey)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("listenKey");
    }
}
