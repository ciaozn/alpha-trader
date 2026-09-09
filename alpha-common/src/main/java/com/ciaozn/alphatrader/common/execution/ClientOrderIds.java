package com.ciaozn.alphatrader.common.execution;

import java.util.regex.Pattern;

/**
 * {@code clientOrderId = strategyId + timestamp + seq} (DESIGN §9.1), the global idempotency
 * key every order carries (FR-EX-02).
 *
 * <p>Binance accepts at most 36 characters from {@code [.A-Za-z0-9:/_-]}, so the timestamp and
 * the sequence are written in base 36. That leaves room for a 20-character strategy id plus
 * roughly 1.6 million orders per process. Within a process the sequence keeps ids unique;
 * across restarts the timestamp does - wall clock in live, virtual clock in backtest, so a
 * deterministic replay reproduces the very same ids (NFR-04).
 *
 * <p>Retrying a submission must reuse the id: that is what makes the exchange reject the
 * duplicate instead of filling twice.
 */
public final class ClientOrderIds {

    public static final int MAX_LENGTH = 36;

    private static final Pattern ALLOWED = Pattern.compile("[.A-Za-z0-9:/_-]{1," + MAX_LENGTH + "}");

    private ClientOrderIds() {
    }

    public static String of(String strategyId, long timestampMillis, long sequence) {
        if (strategyId == null || strategyId.isBlank()) {
            throw new IllegalArgumentException("strategyId must not be blank");
        }
        if (timestampMillis < 0 || sequence < 0) {
            throw new IllegalArgumentException(
                    "timestamp and sequence must be >= 0, got " + timestampMillis + " and " + sequence);
        }
        String id = strategyId + "-" + Long.toString(timestampMillis, 36) + "-" + Long.toString(sequence, 36);
        if (!ALLOWED.matcher(id).matches()) {
            throw new IllegalArgumentException("clientOrderId '" + id + "' is not usable: exchanges accept 1-"
                    + MAX_LENGTH + " characters from [.A-Za-z0-9:/_-] - shorten the strategy id");
        }
        return id;
    }
}
