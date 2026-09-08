package com.ciaozn.alphatrader.gateway;

import com.ciaozn.alphatrader.common.event.KlineEvent;
import com.ciaozn.alphatrader.common.model.Interval;
import com.ciaozn.alphatrader.gateway.binance.BinanceKlineParser;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

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
}
