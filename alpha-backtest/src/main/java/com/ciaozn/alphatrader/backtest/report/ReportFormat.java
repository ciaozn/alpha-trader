package com.ciaozn.alphatrader.backtest.report;

import com.ciaozn.alphatrader.common.model.Money;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * How the report turns numbers into text. Every rule here exists because the obvious alternative
 * is either lossy or non-reproducible.
 *
 * <ul>
 *   <li><b>No display rounding.</b> Amounts are printed exactly as computed, with trailing zeros
 *       stripped. A report that re-rounded to two decimals could show a real loss as
 *       {@code -0.00}, and would make the printed numbers disagree with the metrics object the
 *       CI thresholds are asserted against.</li>
 *   <li><b>{@link Locale#ROOT} everywhere.</b> {@code String.format("%,.2f")} follows the default
 *       locale, so the same run would print {@code 1,234.56} on one machine and {@code 1.234,56}
 *       on another - a direct violation of SC-02 that no test on a single machine would catch.</li>
 *   <li><b>UTC for every timestamp.</b> Business time is exchange time; rendering it in the
 *       machine's zone would make the report change when the run moves.</li>
 *   <li><b>Undefined is a word, not a zero.</b> {@link PerformanceMetrics} reports an undefined
 *       statistic as an empty {@link Optional}; printing {@code 0} there would read as "no risk"
 *       or "no losses" and would silently satisfy a threshold assertion.</li>
 * </ul>
 */
final class ReportFormat {

    /** What an undefined statistic prints as. Never a number. */
    static final String UNDEFINED = "未定义";

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm", Locale.ROOT).withZone(ZoneOffset.UTC);
    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);

    private ReportFormat() {
    }

    /**
     * Exact, with trailing zeros stripped. Zero prints as {@code 0} and never as the {@code 0E-8}
     * its scale would suggest - guaranteed by the JDK since 8, and pinned by a test because it once
     * was not.
     */
    static String amount(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    /** Exact percentage: multiplying by 100 is exact in decimal, so nothing is lost. */
    static String percent(BigDecimal fraction) {
        return amount(Money.of(fraction.multiply(ONE_HUNDRED))) + "%";
    }

    static String percent(OptionalDouble fraction) {
        return fraction.isPresent() ? doublePercent(fraction.getAsDouble()) : UNDEFINED;
    }

    static String ratio(OptionalDouble value) {
        return value.isPresent() ? fixed(value.getAsDouble(), 3) : UNDEFINED;
    }

    static String amount(Optional<BigDecimal> value) {
        return value.map(ReportFormat::amount).orElse(UNDEFINED);
    }

    /**
     * Only for values that are already doubles by nature - Sharpe, annualized return - where the
     * metric itself carries no exact decimal form. Fixed scale, ROOT locale, half-up.
     */
    static String fixed(double value, int scale) {
        return String.format(Locale.ROOT, "%." + scale + "f", value);
    }

    private static String doublePercent(double fraction) {
        return fixed(fraction * 100.0, 4) + "%";
    }

    static String time(long epochMillis) {
        return TIME.format(Instant.ofEpochMilli(epochMillis));
    }

    /** Escape every interpolated string: symbol and client-order-id text originates in config and data files. */
    static String escape(String text) {
        StringBuilder out = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
