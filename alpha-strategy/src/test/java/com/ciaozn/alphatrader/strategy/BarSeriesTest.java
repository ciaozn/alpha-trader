package com.ciaozn.alphatrader.strategy;

import com.ciaozn.alphatrader.common.model.Kline;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BarSeriesTest {

    private static Kline bar(long openTime, double close) {
        BigDecimal price = BigDecimal.valueOf(close);
        return new Kline(openTime, price, price.add(BigDecimal.ONE), price.subtract(BigDecimal.ONE),
                price, BigDecimal.TEN, openTime + 3_600_000L);
    }

    @Test
    void keepsBarsInChronologicalOrder() {
        BarSeries series = new BarSeries(8);
        assertThat(series.offer(bar(1000L, 10.0))).isTrue();
        assertThat(series.offer(bar(2000L, 11.0))).isTrue();
        assertThat(series.offer(bar(3000L, 12.0))).isTrue();

        assertThat(series.size()).isEqualTo(3);
        assertThat(series.last().close()).isEqualByComparingTo(BigDecimal.valueOf(12.0));
        assertThat(series.ago(1).close()).isEqualByComparingTo(BigDecimal.valueOf(11.0));
        assertThat(series.ago(2).close()).isEqualByComparingTo(BigDecimal.valueOf(10.0));
        assertThat(series.hasBars(3)).isTrue();
        assertThat(series.hasBars(4)).isFalse();
    }

    @Test
    void dropsDuplicateDeliveryOfSameBar() {
        BarSeries series = new BarSeries(8);
        series.offer(bar(1000L, 10.0));
        // exchanges re-push the same bar (spec edge case 1) - it must not extend the series,
        // otherwise a strategy would re-evaluate and could re-emit the same signal
        assertThat(series.offer(bar(1000L, 10.5))).isFalse();
        assertThat(series.size()).isEqualTo(1);
        assertThat(series.duplicateCount()).isEqualTo(1);
        assertThat(series.staleCount()).isZero();
    }

    @Test
    void dropsOutOfOrderStaleBar() {
        BarSeries series = new BarSeries(8);
        series.offer(bar(2000L, 11.0));
        assertThat(series.offer(bar(1000L, 10.0))).isFalse();
        assertThat(series.size()).isEqualTo(1);
        assertThat(series.staleCount()).isEqualTo(1);
        assertThat(series.duplicateCount()).isZero();
    }

    @Test
    void evictsOldestBarBeyondCapacity() {
        BarSeries series = new BarSeries(4);
        for (int i = 1; i <= 6; i++) {
            series.offer(bar(i * 1000L, i));
        }
        assertThat(series.size()).isEqualTo(4);
        assertThat(series.capacity()).isEqualTo(4);
        assertThat(series.window(4)).extracting(Kline::openTime).containsExactly(3000L, 4000L, 5000L, 6000L);
        assertThatThrownBy(() -> series.ago(4)).isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test
    void exposesNumericWindowsOldestFirst() {
        BarSeries series = new BarSeries(16);
        for (int i = 1; i <= 5; i++) {
            series.offer(bar(i * 1000L, i * 10.0));
        }
        assertThat(series.closes(3)).containsExactly(30.0, 40.0, 50.0);
        assertThat(series.highs(2)).containsExactly(41.0, 51.0);
        assertThat(series.lows(2)).containsExactly(39.0, 49.0);

        List<Kline> window = series.window(2);
        assertThat(window).hasSize(2);
        assertThatThrownBy(() -> window.add(bar(9000L, 1.0)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsOversizedWindowAndTinyCapacity() {
        BarSeries series = new BarSeries(4);
        series.offer(bar(1000L, 10.0));
        assertThatThrownBy(() -> series.window(2)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> new BarSeries(1)).isInstanceOf(IllegalArgumentException.class);
    }
}
