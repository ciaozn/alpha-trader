package com.ciaozn.alphatrader.gateway.okx;

import com.ciaozn.alphatrader.common.model.OrderStatus;
import com.ciaozn.alphatrader.common.model.Side;
import com.ciaozn.alphatrader.gateway.OpenOrder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code /api/v5/trade/orders-pending} -> resting orders (T406).
 *
 * <p>The endpoint only returns live orders, so unlike Binance there is no status filter to apply -
 * but the status is still mapped into this system's vocabulary rather than passed through, because
 * "live at OKX" means {@code live} / {@code partially_filled} here.
 */
public final class OkxOpenOrdersParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private OkxOpenOrdersParser() {
    }

    public static List<OpenOrder> parse(String body) {
        try {
            List<OpenOrder> orders = new ArrayList<>();
            for (JsonNode row : MAPPER.readTree(body).path("data")) {
                String state = row.path("state").asText("live");
                // "partially_filled" in OKX is still a resting order that reconciliation must see.
                OrderStatus status = state.startsWith("partial")
                        ? OrderStatus.PARTIALLY_FILLED : OrderStatus.SUBMITTED;
                orders.add(new OpenOrder(
                        row.path("clOrdId").asText(),
                        OkxSymbols.toUnified(row.path("instId").asText()),
                        Side.valueOf(row.path("side").asText().toUpperCase()),
                        new BigDecimal(row.path("sz").asText("0")),
                        new BigDecimal(row.path("px").asText("0")),
                        status));
            }
            return List.copyOf(orders);
        } catch (IOException e) {
            throw new UncheckedIOException("Malformed OKX pending-orders response", e);
        }
    }
}
