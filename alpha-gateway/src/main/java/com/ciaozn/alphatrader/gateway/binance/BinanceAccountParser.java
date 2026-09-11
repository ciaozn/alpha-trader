package com.ciaozn.alphatrader.gateway.binance;

import com.ciaozn.alphatrader.gateway.AccountSnapshot;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;

/**
 * {@code /fapi/v2/account} -> {@link AccountSnapshot} (T316).
 *
 * <p><b>Equity is {@code totalMarginBalance}</b>, not the wallet balance: margin balance includes
 * unrealized P&L, and equity is the number every risk rule divides by. Using the wallet balance
 * would make a losing position look like a smaller account rather than a smaller account.
 *
 * <p>Margin ratio is computed rather than read, because this endpoint does not report one:
 * maintenance-margin utilisation is {@code totalMarginBalance / totalMaintMargin}. When there is no
 * maintenance margin (flat account) the ratio is reported as {@link #NO_MAINTENANCE_MARGIN} instead
 * of infinity - a rule comparing "ratio >= 150%" must not have to know what division by zero means.
 */
public final class BinanceAccountParser {

    /** Reported when the account holds no maintenance margin (no open risk to measure). */
    public static final BigDecimal NO_MAINTENANCE_MARGIN = new BigDecimal("9999");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private BinanceAccountParser() {
    }

    public static AccountSnapshot parse(String body) {
        try {
            JsonNode root = MAPPER.readTree(body);
            BigDecimal equity = decimal(root, "totalMarginBalance");
            BigDecimal available = decimal(root, "availableBalance");
            BigDecimal maintenance = decimal(root, "totalMaintMargin");
            BigDecimal ratio = maintenance.signum() == 0
                    ? NO_MAINTENANCE_MARGIN
                    : equity.multiply(BigDecimal.valueOf(100)).divide(maintenance, 8, java.math.RoundingMode.HALF_UP);
            return new AccountSnapshot(equity, available, ratio);
        } catch (IOException e) {
            throw new UncheckedIOException("Malformed account response", e);
        }
    }

    private static BigDecimal decimal(JsonNode root, String field) {
        JsonNode node = root.get(field);
        return node == null ? BigDecimal.ZERO : new BigDecimal(node.asText());
    }
}
