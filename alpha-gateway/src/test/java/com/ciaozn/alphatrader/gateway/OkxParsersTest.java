package com.ciaozn.alphatrader.gateway;

import com.ciaozn.alphatrader.common.event.KlineEvent;
import com.ciaozn.alphatrader.common.model.Direction;
import com.ciaozn.alphatrader.common.model.Interval;
import com.ciaozn.alphatrader.common.model.OrderStatus;
import com.ciaozn.alphatrader.gateway.okx.OkxAccountParser;
import com.ciaozn.alphatrader.gateway.okx.OkxKlineParser;
import com.ciaozn.alphatrader.gateway.okx.OkxOpenOrdersParser;
import com.ciaozn.alphatrader.gateway.okx.OkxOrderAckParser;
import com.ciaozn.alphatrader.gateway.okx.OkxPositionParser;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class OkxParsersTest {

    private static final String CANDLE = """
            {"arg":{"channel":"candle1H","instId":"BTC-USDT-SWAP"},
             "data":[{"instId":"BTC-USDT-SWAP",
                      "candle":["1693996400000","26000.1","26100","25990","26050.5","123.4","123.4","3200000","1"]}]}
            """;

    private static final String CANDLE_OPEN = CANDLE.replace("\"1\"]}", "\"0\"]}");

    @Test
    void candleMapsToAUnifiedKline() {
        Optional<KlineEvent> event = OkxKlineParser.parse(CANDLE, Interval.H1);

        assertThat(event).isPresent();
        assertThat(event.get().symbol().unified()).isEqualTo("BTCUSDT.PERP");
        assertThat(event.get().closed()).isTrue();
        assertThat(event.get().kline().close()).isEqualByComparingTo(new BigDecimal("26050.5"));
        assertThat(event.get().kline().volume()).isEqualByComparingTo(new BigDecimal("123.4"));
        // Close time is derived from the bar's open plus the interval, because OKX does not send one.
        assertThat(event.get().kline().closeTime()).isEqualTo(1693996400000L + 3_600_000L - 1);
    }

    @Test
    void anUnconfirmedBarIsPublishedAsNotClosed() {
        assertThat(OkxKlineParser.parse(CANDLE_OPEN, Interval.H1).orElseThrow().closed()).isFalse();
    }

    @Test
    void nonCandleFramesAreIgnoredRatherThanGuessed() {
        assertThat(OkxKlineParser.parse("{\"event\":\"subscribe\",\"arg\":{}}", Interval.H1)).isEmpty();
        assertThat(OkxKlineParser.parse("{\"data\":[]}", Interval.H1)).isEmpty();
    }

    @Test
    void orderAckNeedsBothResultCodesToBeZero() {
        assertThat(OkxOrderAckParser.parse("{\"code\":\"0\",\"data\":[{\"ordId\":\"3122698\",\"sCode\":\"0\"}]}",
                "c-1").accepted()).isTrue();

        // Understood request, refused order: the distinction that decides between SUBMITTED and REJECTED.
        var refused = OkxOrderAckParser.parse(
                "{\"code\":\"0\",\"data\":[{\"sCode\":\"51008\",\"sMsg\":\"Order failed. Insufficient balance\"}]}",
                "c-2");
        assertThat(refused.accepted()).isFalse();
        assertThat(refused.rejectReason()).contains("51008").contains("Insufficient balance");

        var malformed = OkxOrderAckParser.parse("{\"code\":\"50011\",\"msg\":\"Rate limit\"}", "c-3");
        assertThat(malformed.accepted()).isFalse();
        assertThat(malformed.rejectReason()).contains("50011");
    }

    @Test
    void positionsUseTheSignedNetQuantity() {
        String body = """
                {"code":"0","data":[
                  {"instId":"BTC-USDT-SWAP","pos":"-0.5","avgPx":"50000","upl":"-12.5"},
                  {"instId":"ETH-USDT-SWAP","pos":"0","avgPx":"0","upl":"0"}]}
                """;
        var positions = OkxPositionParser.parse(body);

        assertThat(positions).hasSize(1);
        assertThat(positions.get(0).symbol().unified()).isEqualTo("BTCUSDT.PERP");
        assertThat(positions.get(0).direction()).isEqualTo(Direction.SHORT);
        assertThat(positions.get(0).qty()).isEqualByComparingTo(new BigDecimal("0.5"));
    }

    @Test
    void pendingOrdersMapStateToThisSystemsVocabulary() {
        String body = """
                {"code":"0","data":[
                  {"instId":"BTC-USDT-SWAP","clOrdId":"a-1","side":"buy","sz":"0.5","px":"0","state":"live"},
                  {"instId":"BTC-USDT-SWAP","clOrdId":"a-2","side":"sell","sz":"1","px":"0","state":"partially_filled"}]}
                """;
        var orders = OkxOpenOrdersParser.parse(body);

        assertThat(orders).hasSize(2);
        assertThat(orders.get(0).status()).isEqualTo(OrderStatus.SUBMITTED);
        assertThat(orders.get(1).status()).isEqualTo(OrderStatus.PARTIALLY_FILLED);
        assertThat(orders.get(0).clientOrderId()).isEqualTo("a-1");
    }

    @Test
    void accountNeedsBothBodiesForEquityAndMarginRatio() {
        String balance = """
                {"code":"0","data":[{"totalEq":"1010.5","details":[
                  {"ccy":"USDT","availBal":"900.25","cashBal":"950"}]}]}
                """;
        String risk = "{\"code\":\"0\",\"data\":[{\"mgnRatio\":\"420.5\"}]}";

        var account = OkxAccountParser.parse(balance, risk);

        assertThat(account.totalEquity()).isEqualByComparingTo(new BigDecimal("1010.5"));
        assertThat(account.availableMargin()).isEqualByComparingTo(new BigDecimal("900.25"));
        assertThat(account.marginRatio()).isEqualByComparingTo(new BigDecimal("420.5"));

        // With no risk body the ratio is the sentinel, not a silently safe zero: the account rule
        // must not read "unknown" as "plenty of margin".
        assertThat(OkxAccountParser.parse(balance, null).marginRatio())
                .isEqualByComparingTo(OkxAccountParser.NO_MAINTENANCE_MARGIN);
    }
}
