package com.ciaozn.alphatrader.gateway.binance;

import com.ciaozn.alphatrader.common.model.Side;
import com.ciaozn.alphatrader.common.model.Symbol;
import com.ciaozn.alphatrader.gateway.OpenOrder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code /fapi/v1/openOrders} -> the orders still resting at the exchange (T316), the exchange side
 * of reconciliation (FR-EX-04).
 *
 * <p>Rows whose status is not {@code NEW} or {@code PARTIALLY_FILLED} are dropped: the endpoint is
 * the authority on what is resting, and a row that says FILLED would otherwise be counted as an
 * order we believe is open, which is the exact disagreement reconciliation exists to find.
 *
 * <p>Quantity is the order's {@code origQty}, not the filled quantity - reconciliation compares
 * "what did we ask for", while how much of it filled is a fill's job.
 */
public final class BinanceOpenOrdersParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private BinanceOpenOrdersParser() {
    }

    public static List<OpenOrder> parse(String body) {
        try {
            JsonNode root = MAPPER.readTree(body);
            List<OpenOrder> orders = new ArrayList<>();
            for (JsonNode row : root) {
                String status = row.path("status").asText("");
                if (!status.equals("NEW") && !status.equals("PARTIALLY_FILLED")) {
                    continue;
                }
                orders.add(new OpenOrder(
                        row.path("clientOrderId").asText(),
                        Symbol.parse(row.path("symbol").asText() + ".PERP"),
                        Side.valueOf(row.path("side").asText()),
                        new BigDecimal(row.path("origQty").asText()),
                        new BigDecimal(row.path("price").asText("0")),
                        BinanceOrderStatus.to(status)));
            }
            return List.copyOf(orders);
        } catch (IOException e) {
            throw new UncheckedIOException("Malformed openOrders response", e);
        }
    }
}
