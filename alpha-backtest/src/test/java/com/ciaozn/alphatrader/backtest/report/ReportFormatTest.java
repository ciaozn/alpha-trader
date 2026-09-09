package com.ciaozn.alphatrader.backtest.report;

import com.ciaozn.alphatrader.common.model.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The formatting rules the whole report rests on. Each one is here because the obvious alternative
 * is either lossy or machine-dependent, and both failures are invisible on the machine that wrote
 * the code.
 */
class ReportFormatTest {

    @Test
    void amountsPrintExactlyWithTrailingZerosStripped() {
        assertThat(ReportFormat.amount(Money.of("10000"))).isEqualTo("10000");
        assertThat(ReportFormat.amount(Money.of("10005.5"))).isEqualTo("10005.5");
        assertThat(ReportFormat.amount(Money.of("-1.00500000"))).isEqualTo("-1.005");
        assertThat(ReportFormat.amount(Money.of("0.00000001"))).isEqualTo("0.00000001");
    }

    @Test
    void zeroPrintsAsZeroAndNotAsTheScaleItHappenedToCarry() {
        // Money.of(0) carries scale 8. Printing "0E-8" in a report would be nonsense, and getting
        // "0" instead used to depend on JDK-6480539 (fixed in Java 8), so it stays pinned.
        assertThat(ReportFormat.amount(Money.zero())).isEqualTo("0");
        assertThat(ReportFormat.amount(new BigDecimal("0.00000000"))).isEqualTo("0");
    }

    @Test
    void noAmountIsEverRoundedForDisplay() {
        // A report that re-rounded to two decimals would show a real loss as -0.00, and would make
        // the printed numbers disagree with the metrics the CI thresholds are asserted against.
        assertThat(ReportFormat.amount(Money.of("-0.004"))).isEqualTo("-0.004");
        assertThat(ReportFormat.amount(Money.of("0.12345678"))).isEqualTo("0.12345678");
        assertThat(ReportFormat.amount(Money.of("1234567.891"))).isEqualTo("1234567.891");
    }

    @Test
    void percentagesAreExactMultiplicationsNotDoubleConversions() {
        assertThat(ReportFormat.percent(Money.of("0.0432"))).isEqualTo("4.32%");
        assertThat(ReportFormat.percent(Money.of("-0.5"))).isEqualTo("-50%");
        assertThat(ReportFormat.percent(Money.of("1.05"))).isEqualTo("105%");
        // 1/3 of a percent has no exact two-decimal form; it must still print in full.
        assertThat(ReportFormat.percent(Money.of("0.00333333"))).isEqualTo("0.333333%");
    }

    @Test
    void undefinedNeverRendersAsANumber() {
        assertThat(ReportFormat.percent(OptionalDouble.empty())).isEqualTo(ReportFormat.UNDEFINED);
        assertThat(ReportFormat.ratio(OptionalDouble.empty())).isEqualTo(ReportFormat.UNDEFINED);
        assertThat(ReportFormat.amount(Optional.<BigDecimal>empty())).isEqualTo(ReportFormat.UNDEFINED);
        assertThat(ReportFormat.UNDEFINED).isNotEqualTo("0").isNotEqualTo("0%").isNotEmpty();
    }

    @Test
    void definedOptionalsRenderAsTheirValue() {
        assertThat(ReportFormat.percent(OptionalDouble.of(0.0432))).isEqualTo("4.3200%");
        assertThat(ReportFormat.ratio(OptionalDouble.of(1.23456))).isEqualTo("1.235");
        assertThat(ReportFormat.amount(Optional.of(Money.of("12.5")))).isEqualTo("12.5");
    }

    @Test
    void doubleFormattingIgnoresTheMachineLocale() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("de-DE"));
            assertThat(ReportFormat.fixed(1234.5678, 2)).isEqualTo("1234.57");
            Locale.setDefault(Locale.forLanguageTag("ar-SA"));
            assertThat(ReportFormat.fixed(1234.5678, 2)).isEqualTo("1234.57");
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    void timestampsAreUtcRegardlessOfTheMachineZone() {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Kiritimati"));
            String utcPlus14 = ReportFormat.time(1_700_006_400_000L);
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Pago_Pago"));
            String utcMinus11 = ReportFormat.time(1_700_006_400_000L);

            // T0 across the suite is a UTC midnight, which is what makes the 8h funding
            // boundaries land on it; pinning the exact text also proves this is UTC and not
            // the machine's zone, which would have printed 08:00 here.
            assertThat(utcPlus14).isEqualTo(utcMinus11).isEqualTo("2023-11-15 00:00");
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test
    void escapingCoversEveryCharacterThatCouldOpenMarkup() {
        assertThat(ReportFormat.escape("<script>&\"'</script>"))
                .isEqualTo("&lt;script&gt;&amp;&quot;&#39;&lt;/script&gt;");
        assertThat(ReportFormat.escape("plain 中文 123")).isEqualTo("plain 中文 123");
    }

    @Test
    void escapingIsIdempotentEnoughToDetectDoubleEscaping() {
        // The renderer escapes once, at the point a value enters markup. If a caller also escaped,
        // the reader would see the entity itself - which is what this pins as wrong.
        assertThat(ReportFormat.escape(ReportFormat.escape("&"))).isEqualTo("&amp;amp;");
        assertThat(ReportFormat.escape("&")).isEqualTo("&amp;");
    }
}
