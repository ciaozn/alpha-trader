package com.ciaozn.alphatrader.execution;

import com.ciaozn.alphatrader.common.event.FillEvent;
import com.ciaozn.alphatrader.common.event.OrderRequestEvent;
import com.ciaozn.alphatrader.common.model.OrderStatus;
import com.ciaozn.alphatrader.common.model.OrderType;
import com.ciaozn.alphatrader.common.model.Side;
import com.ciaozn.alphatrader.common.model.Symbol;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T309: the {@link OrderStore} contract, exercised against the implementation that has nothing to
 * hide behind - no SQL, no dialect, no file.
 *
 * <p>These are the assertions T310's JDBC store must also pass, and they are written against the
 * interface for exactly that reason: a round-trip test that knew about SQLite would be a test of
 * SQLite. Two of them matter more than they look.
 *
 * <p>The <b>scale</b> one is 取舍 15's whole argument in a single assertion: {@code 68000.10} must
 * come back as {@code 68000.10}, not {@code 68000.1}. AssertJ's {@code isEqualTo} on a BigDecimal is
 * scale-sensitive, so this passes here trivially (the object is stored by reference) and fails loudly
 * the day it is pointed at a NUMERIC or DECIMAL column, which is the point of writing it now.
 *
 * <p>The <b>replace-don't-append</b> one is what keeps {@code orders} a table of state: an order that
 * is saved on every migration must still be one row, or {@code findOpen()} reports the same order as
 * both resting and filled and reconciliation starts arguing with itself.
 */
class InMemoryOrderStoreTest {

    private static final Symbol BTC = Symbol.parse("BTCUSDT.PERP");
    private static final Symbol ETH = Symbol.parse("ETHUSDT.PERP");
    private static final long T0 = 1_700_000_000_000L;

    private final InMemoryOrderStore store = new InMemoryOrderStore();

    private static OrderRequestEvent market(String clientOrderId, Symbol symbol, String qty) {
        return OrderRequestEvent.of(clientOrderId, symbol, Side.BUY, OrderType.MARKET,
                new BigDecimal(qty), null, T0);
    }

    private static FillEvent fill(String clientOrderId, Symbol symbol, String price, String qty, String fee) {
        return FillEvent.of(clientOrderId, symbol, Side.BUY, new BigDecimal(price), new BigDecimal(qty),
                new BigDecimal(fee), T0 + 1);
    }

    @Test
    void anOrderSavedIsTheOrderReadBack() {
        OrderRecord row = OrderRecord.ofNew(market("ma-cross-btc-1", BTC, "0.1500"), T0)
                .after(OrderStatus.SUBMITTED, "3847291055", null, null, null, T0 + 5);

        store.save(row);

        assertThat(store.find("ma-cross-btc-1")).contains(row);
    }

    @Test
    void savingTheSameOrderAgainReplacesTheRowAndLeavesItWhereItWas() {
        store.save(orderWithStatus("ma-cross-btc-1", OrderStatus.NEW));
        store.save(orderWithStatus("rsi-reversal-btc-2", OrderStatus.NEW));

        store.save(orderWithStatus("ma-cross-btc-1", OrderStatus.SUBMITTED));

        assertThat(store.find("ma-cross-btc-1").orElseThrow().status()).isEqualTo(OrderStatus.SUBMITTED);
        assertThat(store.findOpen())
                .as("one row per clientOrderId, and an update does not move it to the back")
                .extracting(OrderRecord::clientOrderId)
                .containsExactly("ma-cross-btc-1", "rsi-reversal-btc-2");

        store.save(orderWithStatus("ma-cross-btc-1", OrderStatus.FILLED));

        assertThat(store.findOpen())
                .as("a filled order is one row that stopped being open, not a second row")
                .extracting(OrderRecord::clientOrderId)
                .containsExactly("rsi-reversal-btc-2");
    }

    @Test
    void findOpenReturnsOnlyTheOrdersTheExchangeCouldStillChange() {
        store.save(orderWithStatus("a-new", OrderStatus.NEW));
        store.save(orderWithStatus("b-submitted", OrderStatus.SUBMITTED));
        store.save(orderWithStatus("c-partial", OrderStatus.PARTIALLY_FILLED));
        store.save(orderWithStatus("d-filled", OrderStatus.FILLED));
        store.save(orderWithStatus("e-canceled", OrderStatus.CANCELED));
        store.save(orderWithStatus("f-rejected", OrderStatus.REJECTED));

        assertThat(store.findOpen())
                .extracting(OrderRecord::clientOrderId)
                .containsExactly("a-new", "b-submitted", "c-partial");
    }

    @Test
    void fillsStayWithTheirOwnOrderInTheOrderTheyWereStored() {
        FillEvent first = fill("ma-cross-btc-1", BTC, "68000.10", "0.0500", "0.05100000");
        FillEvent second = fill("ma-cross-btc-1", BTC, "68001.20", "0.1000", "0.10200000");
        FillEvent other = fill("rsi-reversal-eth-2", ETH, "3500.05", "1.2000", "0.10500000");
        store.saveFill(second);
        store.saveFill(other);
        store.saveFill(first);

        assertThat(store.fills("ma-cross-btc-1")).containsExactly(second, first);
        assertThat(store.fills("rsi-reversal-eth-2")).containsExactly(other);
    }

    @Test
    void anOrderThisProcessNeverHeldIsAbsentRatherThanEmpty() {
        // FR-EX-02's idempotency check needs a negative answer it can trust: "not in the store" has
        // to mean "never sent", not "cannot tell".
        assertThat(store.find("never-sent")).isEmpty();
        assertThat(store.fills("never-sent")).isEmpty();
        assertThat(store.findOpen()).isEmpty();
    }

    @Test
    void pricesAndQuantitiesComeBackWithTheScaleTheyWentInWith() {
        OrderRequestEvent limit = OrderRequestEvent.of("ma-cross-btc-1", BTC, Side.SELL, OrderType.LIMIT,
                new BigDecimal("0.1500"), new BigDecimal("68000.10"), T0);
        store.save(OrderRecord.ofNew(limit, T0)
                .after(OrderStatus.PARTIALLY_FILLED, "3847291055", new BigDecimal("0.0500"),
                        new BigDecimal("68000.10"), null, T0 + 5));
        store.saveFill(fill("ma-cross-btc-1", BTC, "68000.10", "0.0500", "0.05100000"));

        OrderRecord row = store.find("ma-cross-btc-1").orElseThrow();
        assertThat(row.qty()).isEqualTo(new BigDecimal("0.1500"));
        assertThat(row.limitPrice()).isEqualTo(new BigDecimal("68000.10"));
        assertThat(row.filledQty()).isEqualTo(new BigDecimal("0.0500"));
        assertThat(row.avgFillPrice()).isEqualTo(new BigDecimal("68000.10"));

        FillEvent stored = store.fills("ma-cross-btc-1").getFirst();
        assertThat(stored.price()).isEqualTo(new BigDecimal("68000.10"));
        assertThat(stored.qty()).isEqualTo(new BigDecimal("0.0500"));
        assertThat(stored.fee()).isEqualTo(new BigDecimal("0.05100000"));
    }

    @Test
    void aReaderCannotWriteThroughTheListItWasHanded() {
        store.save(orderWithStatus("a-new", OrderStatus.NEW));
        store.saveFill(fill("a-new", BTC, "68000.10", "0.0500", "0.05100000"));

        List<OrderRecord> open = store.findOpen();
        List<FillEvent> fills = store.fills("a-new");
        assertThatThrownBy(() -> open.add(orderWithStatus("injected", OrderStatus.NEW)))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> fills.add(fill("a-new", BTC, "1", "1", "0")))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> fills.clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static OrderRecord orderWithStatus(String clientOrderId, OrderStatus status) {
        OrderRecord row = OrderRecord.ofNew(market(clientOrderId, BTC, "0.1500"), T0);
        return status == OrderStatus.NEW ? row : row.after(status, "3847291055", null, null, null, T0 + 1);
    }
}
