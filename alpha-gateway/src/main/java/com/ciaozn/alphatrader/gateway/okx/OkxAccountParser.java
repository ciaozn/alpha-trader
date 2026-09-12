package com.ciaozn.alphatrader.gateway.okx;

import com.ciaozn.alphatrader.gateway.AccountSnapshot;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;

/**
 * OKX balance/risk responses -> {@link AccountSnapshot} (T406).
 *
 * <p>Two calls, two parsers, one snapshot: {@code /account/balance} carries equity and available
 * balance, {@code /account/account-position-risk} carries the maintenance-margin ratio that the
 * account rule needs. Reading only the balance would leave the ratio unknown, and defaulting it to
 * "safe" would silently disable a risk rule on this exchange - the one failure mode worth two
 * requests to avoid.
 */
public final class OkxAccountParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** Reported when the exchange holds no maintenance margin (flat account). */
    public static final BigDecimal NO_MAINTENANCE_MARGIN = new BigDecimal("9999");

    private OkxAccountParser() {
    }

    /** @param riskBody response of {@code /api/v5/account/account-position-risk}; may be empty */
    public static AccountSnapshot parse(String balanceBody, String riskBody) {
        try {
            JsonNode balance = MAPPER.readTree(balanceBody).path("data").path(0);
            BigDecimal equity = new BigDecimal(balance.path("totalEq").asText("0"));
            BigDecimal available = usdtAvailable(balance);
            BigDecimal marginRatio = marginRatio(riskBody);
            return new AccountSnapshot(equity, available, marginRatio);
        } catch (IOException e) {
            throw new UncheckedIOException("Malformed OKX account response", e);
        }
    }

    private static BigDecimal usdtAvailable(JsonNode balance) {
        for (JsonNode detail : balance.path("details")) {
            if ("USDT".equals(detail.path("ccy").asText())) {
                // availBal is the amount free for new orders; falling back to cashBal would count
                // margin already committed to open positions as spendable.
                String value = detail.path("availBal").asText("");
                return value.isBlank() ? new BigDecimal(detail.path("cashBal").asText("0")) : new BigDecimal(value);
            }
        }
        return BigDecimal.ZERO;
    }

    private static BigDecimal marginRatio(String riskBody) {
        if (riskBody == null || riskBody.isBlank()) {
            return NO_MAINTENANCE_MARGIN;
        }
        try {
            String raw = MAPPER.readTree(riskBody).path("data").path(0).path("mgnRatio").asText("");
            return raw.isBlank() ? NO_MAINTENANCE_MARGIN : new BigDecimal(raw);
        } catch (IOException e) {
            throw new UncheckedIOException("Malformed OKX risk response", e);
        }
    }
}
