package com.ciaozn.alphatrader.gateway;

import com.ciaozn.alphatrader.common.event.KlineEvent;
import com.ciaozn.alphatrader.common.model.Interval;
import com.ciaozn.alphatrader.common.model.Kline;
import com.ciaozn.alphatrader.gateway.binance.BinanceKlineParser;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BinanceKlineParserTest {

    private static final String SAMPLE = """
            {
              "stream": "btcusdt@kline_1h",
              "data": {
                "e": "kline",
                "E": 1694000000000,
                "s": "BTCUSDT",
                "k": {
                  "t": 1693996400000,
                  "T": 1693999999999,
                  "s": "BTCUSDT",
                  "i": "1h",
                  "o": "26000.10",
                  "c": "26050.50",
                  "h": "26100.00",
                  "l": "25990.00",
                  "v": "123.456",
                  "x": true
                }
              }
            }
            """;

    @Test
    void parsesStandardKlinePayload() {
        KlineEvent event = BinanceKlineParser.parse(SAMPLE);

        assertThat(event.symbol().unified()).isEqualTo("BTCUSDT.PERP");
        assertThat(event.interval()).isEqualTo(Interval.H1);
        assertThat(event.closed()).isTrue();
        assertThat(event.kline().open()).isEqualByComparingTo(new BigDecimal("26000.10"));
        assertThat(event.kline().close()).isEqualByComparingTo(new BigDecimal("26050.50"));
        assertThat(event.kline().high()).isEqualByComparingTo(new BigDecimal("26100.00"));
        assertThat(event.kline().low()).isEqualByComparingTo(new BigDecimal("25990.00"));
        assertThat(event.kline().volume()).isEqualByComparingTo(new BigDecimal("123.456"));
        // business timestamp = bar close time
        assertThat(event.timestamp()).isEqualTo(1693999999999L);
    }

    @Test
    void rejectsNonKlinePayload() {
        assertThatThrownBy(() -> BinanceKlineParser.parse("{\"stream\":\"x\",\"data\":{}}"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Real /fapi/v1/klines shape: twelve columns per row, only the first seven are ours. */
    private static final String REST_SAMPLE = """
            [
              [1693996400000,"26000.10","26100.00","25990.00","26050.50","123.456",1693999999999,
               "3214567.89",308,"100.000","2600.00","17928899.62484339"],
              [1694000000000,"26050.50","26060.00","25900.00","25950.00","98.70000000",1694003599999,
               "2562000.10",250,"50.000","1300.00","0"]
            ]
            """;

    @Test
    void parsesARestKlineArrayExactlyAsSent() {
        List<Kline> klines = BinanceKlineParser.parseKlines(REST_SAMPLE);

        assertThat(klines).containsExactly(
                new Kline(1693996400000L, new BigDecimal("26000.10"), new BigDecimal("26100.00"),
                        new BigDecimal("25990.00"), new BigDecimal("26050.50"), new BigDecimal("123.456"),
                        1693999999999L),
                new Kline(1694000000000L, new BigDecimal("26050.50"), new BigDecimal("26060.00"),
                        new BigDecimal("25900.00"), new BigDecimal("25950.00"), new BigDecimal("98.70000000"),
                        1694003599999L));
    }

    @Test
    void anEmptyRestArrayIsAnEmptyResult() {
        assertThat(BinanceKlineParser.parseKlines("[]")).isEmpty();
    }

    @Test
    void rejectsARestPayloadThatIsNotAnArray() {
        assertThatThrownBy(() -> BinanceKlineParser.parseKlines("{\"code\":-1121,\"msg\":\"Invalid symbol.\"}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Not a kline array");
    }

    @Test
    void rejectsATruncatedRowInsteadOfSkippingIt() {
        // a silently skipped row would leave a hole in the stored history that only shows up
        // later as a gap in the backtest
        assertThatThrownBy(() -> BinanceKlineParser.parseKlines("[[1693996400000,\"26000.10\",\"26100.00\"]]"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 7 columns");
    }

    @Test
    void rejectsARestRowThatIsNotNumeric() {
        assertThatThrownBy(() -> BinanceKlineParser.parseKlines(
                "[[1693996400000,\"x\",\"26100.00\",\"25990.00\",\"26050.50\",\"123.456\",1693999999999]]"))
                .isInstanceOf(NumberFormatException.class);
    }
}
