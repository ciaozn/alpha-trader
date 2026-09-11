package com.ciaozn.alphatrader.gateway.binance;

import com.ciaozn.alphatrader.common.model.OrderStatus;

import java.util.Locale;

/**
 * Binance execution-report status -> the system's own {@link OrderStatus} (T316/T317).
 *
 * <p>One place, because both the REST responses and the user data stream carry these strings, and
 * two mappings is two chances to read {@code EXPIRED} as something other than CANCELED.
 *
 * <p><b>NEW maps to SUBMITTED</b>, not to NEW: NEW in this system means "not yet handed to the
 * exchange", a state that exists only between the OMS writing the row and the sender's first
 * attempt. Binance's NEW means the opposite - it has the order - so mapping it onto our NEW would
 * say we are holding an order we have already sent.
 *
 * <p>Unknown statuses throw. A status we do not recognise is a contract change, and guessing one
 * means silently mis-bookkeeping a position.
 */
public final class BinanceOrderStatus {

    private BinanceOrderStatus() {
    }

    public static OrderStatus to(String status) {
        if (status == null) {
            throw new IllegalArgumentException("Binance order status must not be null");
        }
        return switch (status.toUpperCase(Locale.ROOT)) {
            case "NEW" -> OrderStatus.SUBMITTED;
            case "PARTIALLY_FILLED" -> OrderStatus.PARTIALLY_FILLED;
            case "FILLED" -> OrderStatus.FILLED;
            case "CANCELED", "CANCELLED", "EXPIRED" -> OrderStatus.CANCELED;
            case "REJECTED" -> OrderStatus.REJECTED;
            default -> throw new IllegalArgumentException("Unknown Binance order status: " + status);
        };
    }
}
