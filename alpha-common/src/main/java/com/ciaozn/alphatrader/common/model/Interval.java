package com.ciaozn.alphatrader.common.model;

import java.time.Duration;

/** K-line interval with exchange mappings. */
public enum Interval {
    M1("1m", Duration.ofMinutes(1)),
    M5("5m", Duration.ofMinutes(5)),
    M15("15m", Duration.ofMinutes(15)),
    H1("1h", Duration.ofHours(1)),
    H4("4h", Duration.ofHours(4)),
    D1("1d", Duration.ofDays(1));

    private final String binanceCode;
    private final Duration duration;

    Interval(String binanceCode, Duration duration) {
        this.binanceCode = binanceCode;
        this.duration = duration;
    }

    public String binanceCode() {
        return binanceCode;
    }

    /** OKX bar format, e.g. "1H", "1D", "5m". */
    public String okxCode() {
        return switch (this) {
            case M1 -> "1m";
            case M5 -> "5m";
            case M15 -> "15m";
            case H1 -> "1H";
            case H4 -> "4H";
            case D1 -> "1D";
        };
    }

    public Duration duration() {
        return duration;
    }

    public static Interval fromBinanceCode(String code) {
        for (Interval i : values()) {
            if (i.binanceCode.equals(code)) {
                return i;
            }
        }
        throw new IllegalArgumentException("Unknown Binance interval: " + code);
    }
}
