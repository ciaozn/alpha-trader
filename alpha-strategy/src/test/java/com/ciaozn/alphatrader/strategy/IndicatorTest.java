package com.ciaozn.alphatrader.strategy;

import com.ciaozn.alphatrader.strategy.indicator.Atr;
import com.ciaozn.alphatrader.strategy.indicator.Ema;
import com.ciaozn.alphatrader.strategy.indicator.Rsi;
import com.ciaozn.alphatrader.strategy.indicator.Sma;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Indicator correctness is pinned against an independent reference implementation
 * (plain Python, textbook formulas: SMA tail average, EMA seeded by SMA then
 * multiplier 2/(n+1), Wilder-smoothed RSI and ATR). The expected numbers below are the
 * reference outputs - they are the contract, not something to "fix" when a test fails.
 */
class IndicatorTest {

    private static final double[] CLOSES = {
            100.0, 101.5, 101.0, 102.25, 103.0, 102.0, 103.5, 104.25, 103.75, 105.0,
            106.0, 105.25, 104.0, 104.5, 106.25, 107.0, 106.5, 105.75, 106.0, 107.5,
            108.0, 107.25, 106.0, 105.0, 106.5, 108.0, 109.5, 109.0, 108.0, 110.0};

    private static double[] highs() {
        double[] values = new double[CLOSES.length];
        for (int i = 0; i < CLOSES.length; i++) {
            values[i] = CLOSES[i] + 0.8;
        }
        return values;
    }

    private static double[] lows() {
        double[] values = new double[CLOSES.length];
        for (int i = 0; i < CLOSES.length; i++) {
            values[i] = CLOSES[i] - 0.9;
        }
        return values;
    }

    @Test
    void smaMatchesReference() {
        assertThat(Sma.of(CLOSES, 5)).isCloseTo(108.9, within(1e-12));
        // previous window: [106.5, 108.0, 109.5, 109.0, 108.0]
        double[] withoutLast = new double[CLOSES.length - 1];
        System.arraycopy(CLOSES, 0, withoutLast, 0, withoutLast.length);
        assertThat(Sma.of(withoutLast, 5)).isCloseTo(108.2, within(1e-12));
    }

    @Test
    void smaStreamsValueByValueAndStaysReadyAfterWarmup() {
        Sma sma = new Sma(5);
        for (int i = 0; i < 4; i++) {
            sma.update(CLOSES[i]);
            assertThat(sma.ready()).isFalse();
            assertThat(sma.value()).isNaN();
        }
        sma.update(CLOSES[4]);
        assertThat(sma.ready()).isTrue();
        // [100.0, 101.5, 101.0, 102.25, 103.0]
        assertThat(sma.value()).isCloseTo(101.55, within(1e-12));

        for (int i = 5; i < CLOSES.length; i++) {
            sma.update(CLOSES[i]);
        }
        assertThat(sma.value()).isCloseTo(108.9, within(1e-12));
    }

    @Test
    void emaMatchesReference() {
        assertThat(Ema.of(CLOSES, 5)).isCloseTo(108.73382344515211, within(1e-12));
    }

    @Test
    void emaSeedsWithSmaOfFirstPeriodValues() {
        Ema ema = new Ema(5);
        for (int i = 0; i < 4; i++) {
            ema.update(CLOSES[i]);
            assertThat(ema.ready()).isFalse();
            assertThat(ema.value()).isNaN();
        }
        ema.update(CLOSES[4]);
        // seed == SMA(5) of the first five values, i.e. the same window the EMA just consumed
        Sma seed = new Sma(5);
        for (int i = 0; i < 5; i++) {
            seed.update(CLOSES[i]);
        }
        assertThat(ema.value()).isCloseTo(seed.value(), within(1e-12));
        assertThat(ema.value()).isCloseTo(101.55, within(1e-12));
    }

    @Test
    void rsiMatchesReferenceWilderSmoothing() {
        assertThat(Rsi.of(CLOSES, 7)).isCloseTo(67.60696159620701, within(1e-12));
    }

    @Test
    void rsiNeedsPeriodPlusOnePrices() {
        Rsi tooShort = new Rsi(7);
        for (int i = 0; i < 7; i++) {
            tooShort.update(CLOSES[i]);
        }
        // 7 prices = 6 changes, one short of the 7-change seed window
        assertThat(tooShort.ready()).isFalse();
        assertThat(tooShort.value()).isNaN();
        assertThat(Rsi.of(java.util.Arrays.copyOf(CLOSES, 7), 7)).isNaN();

        tooShort.update(CLOSES[7]);
        assertThat(tooShort.ready()).isTrue();
        assertThat(tooShort.value()).isCloseTo(79.3103448275862, within(1e-9));

        tooShort.update(CLOSES[8]);
        assertThat(tooShort.value()).isCloseTo(73.40425531914893, within(1e-9));
    }

    @Test
    void rsiIsHundredWhenNothingButGains() {
        Rsi rsi = new Rsi(3);
        for (double price : new double[]{10, 11, 12, 13}) {
            rsi.update(price);
        }
        assertThat(rsi.value()).isEqualTo(100.0);
    }

    @Test
    void rsiIsZeroWhenNothingButLosses() {
        Rsi rsi = new Rsi(3);
        for (double price : new double[]{13, 12, 11, 10}) {
            rsi.update(price);
        }
        assertThat(rsi.value()).isCloseTo(0.0, within(1e-12));
    }

    @Test
    void atrMatchesReferenceWilderSmoothing() {
        assertThat(Atr.of(highs(), lows(), CLOSES, 5)).isCloseTo(2.154876917741937, within(1e-12));
    }

    @Test
    void atrFirstTrueRangeIsHighMinusLow() {
        Atr atr = new Atr(1);
        atr.update(110.0, 90.0, 100.0);
        assertThat(atr.value()).isCloseTo(20.0, within(1e-12));
        // gap up: true range is measured against the previous close
        atr.update(130.0, 120.0, 125.0);
        assertThat(atr.value()).isCloseTo(30.0, within(1e-12));
    }

    @Test
    void atrPercentIsScaleFree() {
        Atr atr = new Atr(5);
        for (int i = 0; i < CLOSES.length; i++) {
            atr.update(highs()[i], lows()[i], CLOSES[i]);
        }
        assertThat(atr.percentOf(110.0)).isCloseTo(2.154876917741937 / 110.0 * 100.0, within(1e-12));
        assertThat(new Atr(5).percentOf(100.0)).isNaN();
    }

    @Test
    void rejectsInvalidPeriodsAndMismatchedArrays() {
        assertThatThrownBy(() -> new Sma(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Ema(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Rsi(1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Atr(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Atr.of(new double[3], new double[2], new double[3], 2))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
