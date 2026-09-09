package com.ciaozn.alphatrader.execution;

import com.ciaozn.alphatrader.common.event.OrderRequestEvent;
import com.ciaozn.alphatrader.common.execution.ClientOrderIds;
import com.ciaozn.alphatrader.common.model.Money;
import com.ciaozn.alphatrader.common.model.OrderStatus;
import com.ciaozn.alphatrader.common.model.OrderType;
import com.ciaozn.alphatrader.common.model.Side;
import com.ciaozn.alphatrader.common.model.Symbol;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T309: what one {@code orders} row holds before anything has happened to it, and what one lifecycle
 * event changes about it.
 *
 * <p>The interesting assertions are the nulls and the unchanged fields. {@link OrderRecord#after}
 * reads a null as "the exchange said nothing about this", which is only correct if the columns it
 * leaves alone are the ones an ack, a fill and a rejection genuinely do not mention - and if the two
 * it must always move do move. Getting that backwards is invisible in a happy-path test: an order row
 * that quietly forgets its exchange id on every partial fill still looks like an order row, right up
 * to the reconciliation that cannot ask the exchange about it.
 *
 * <p>Nothing here tests which migrations are legal. The record is state, not policy; the state
 * machine and its refusals are the OMS's.
 */
class OrderRecordTest {

    private static final Symbol BTC = Symbol.parse("BTCUSDT.PERP");
    private static final long T0 = 1_700_000_000_000L;
    private static final String CLIENT_ID = ClientOrderIds.of("ma-cross-btc", T0, 1);

    /** MARKET, so {@code price} is null - and the quantity carries trailing zeros on purpose. */
    private static OrderRequestEvent request() {
        return OrderRequestEvent.of(CLIENT_ID, BTC, Side.BUY, OrderType.MARKET,
                new BigDecimal("0.1500"), null, T0);
    }

    @Test
    void aNewRowCarriesTheRequestAndNothingThatHasNotHappenedYet() {
        OrderRecord row = OrderRecord.ofNew(request(), T0);

        assertThat(row.clientOrderId()).isEqualTo(CLIENT_ID);
        assertThat(row.symbol()).isEqualTo(BTC);
        assertThat(row.side()).isEqualTo(Side.BUY);
        assertThat(row.orderType()).isEqualTo(OrderType.MARKET);
        assertThat(row.qty()).isEqualTo(new BigDecimal("0.1500"));
        assertThat(row.limitPrice()).as("a MARKET order has no limit price").isNull();
        assertThat(row.status()).isEqualTo(OrderStatus.NEW);

        assertThat(row.exchangeOrderId()).as("the exchange has not answered yet").isNull();
        assertThat(row.filledQty()).isEqualTo(Money.zero());
        assertThat(row.avgFillPrice()).as("nothing has filled").isNull();
        assertThat(row.statusMessage()).as("nobody has explained itself").isNull();

        assertThat(row.createdAt()).isEqualTo(T0);
        assertThat(row.updatedAt()).as("a row that has never moved says so").isEqualTo(T0);
        assertThat(row.isOpen()).isTrue();
    }

    @Test
    void aNullArgumentMeansTheExchangeSaidNothingAboutThatColumn() {
        // One assertion per nullable column, on a transition where the exchange mentions none of them.
        // Stated as its own test because the rule is uniform: three of the four columns happen to be
        // pinned by the lifecycle tests below, and the fourth would only be pinned by accident.
        OrderRecord annotated = OrderRecord.ofNew(request(), T0)
                .after(OrderStatus.SUBMITTED, "3847291055", new BigDecimal("0.0500"),
                        new BigDecimal("68000.10"), "post-only order will not take liquidity", T0 + 5);

        OrderRecord moved = annotated.after(OrderStatus.PARTIALLY_FILLED, null, null, null, null, T0 + 9);

        assertThat(moved.status()).as("the one argument that is never null").isEqualTo(OrderStatus.PARTIALLY_FILLED);
        assertThat(moved.exchangeOrderId()).isEqualTo("3847291055");
        assertThat(moved.filledQty()).isEqualTo(new BigDecimal("0.0500"));
        assertThat(moved.avgFillPrice()).isEqualTo(new BigDecimal("68000.10"));
        assertThat(moved.statusMessage()).isEqualTo("post-only order will not take liquidity");
        assertThat(moved.updatedAt()).isEqualTo(T0 + 9);
    }

    @Test
    void anAckSuppliesTheExchangeIdAndMovesNothingElse() {
        OrderRecord submitted = OrderRecord.ofNew(request(), T0)
                .after(OrderStatus.SUBMITTED, "3847291055", null, null, null, T0 + 5);

        assertThat(submitted.status()).isEqualTo(OrderStatus.SUBMITTED);
        assertThat(submitted.exchangeOrderId()).isEqualTo("3847291055");
        assertThat(submitted.updatedAt()).isEqualTo(T0 + 5);
        assertThat(submitted.createdAt()).as("when it was opened does not move").isEqualTo(T0);
        assertThat(submitted.filledQty()).as("an ack fills nothing").isEqualTo(Money.zero());
        assertThat(submitted.avgFillPrice()).isNull();
        assertThat(submitted.qty()).isEqualTo(new BigDecimal("0.1500"));
        assertThat(submitted.isOpen()).isTrue();
    }

    @Test
    void aPartialFillUpdatesTheFillColumnsAndKeepsTheExchangeId() {
        OrderRecord partial = OrderRecord.ofNew(request(), T0)
                .after(OrderStatus.SUBMITTED, "3847291055", null, null, null, T0 + 5)
                .after(OrderStatus.PARTIALLY_FILLED, null, new BigDecimal("0.0500"),
                        new BigDecimal("68000.10"), null, T0 + 9);

        assertThat(partial.status()).isEqualTo(OrderStatus.PARTIALLY_FILLED);
        assertThat(partial.exchangeOrderId()).as("an ack is not repeated on every update")
                .isEqualTo("3847291055");
        assertThat(partial.filledQty()).isEqualTo(new BigDecimal("0.0500"));
        assertThat(partial.avgFillPrice()).isEqualTo(new BigDecimal("68000.10"));
        assertThat(partial.updatedAt()).isEqualTo(T0 + 9);
        assertThat(partial.isOpen()).as("the rest of it is still on the book").isTrue();
    }

    @Test
    void cancelingTheRemainderKeepsTheAveragePriceOfThePartThatFilled() {
        OrderRecord canceled = OrderRecord.ofNew(request(), T0)
                .after(OrderStatus.SUBMITTED, "3847291055", null, null, null, T0 + 5)
                .after(OrderStatus.PARTIALLY_FILLED, null, new BigDecimal("0.0500"),
                        new BigDecimal("68000.10"), null, T0 + 9)
                .after(OrderStatus.CANCELED, null, null, null, "client canceled the remainder", T0 + 30);

        assertThat(canceled.status()).isEqualTo(OrderStatus.CANCELED);
        assertThat(canceled.statusMessage()).isEqualTo("client canceled the remainder");
        // 0.05 did fill: that is money already moved, and canceling what is left cannot undo it.
        assertThat(canceled.filledQty()).isEqualTo(new BigDecimal("0.0500"));
        assertThat(canceled.avgFillPrice()).isEqualTo(new BigDecimal("68000.10"));
        assertThat(canceled.exchangeOrderId()).isEqualTo("3847291055");
        assertThat(canceled.updatedAt()).isEqualTo(T0 + 30);
        assertThat(canceled.isOpen()).isFalse();
    }

    @Test
    void aRejectionKeepsTheReasonAndClosesTheRow() {
        OrderRecord rejected = OrderRecord.ofNew(request(), T0)
                .after(OrderStatus.SUBMITTED, "3847291055", null, null, null, T0 + 5)
                .after(OrderStatus.REJECTED, null, null, null, "ReduceOnly Order is rejected", T0 + 20);

        assertThat(rejected.status()).isEqualTo(OrderStatus.REJECTED);
        assertThat(rejected.statusMessage()).isEqualTo("ReduceOnly Order is rejected");
        assertThat(rejected.exchangeOrderId()).isEqualTo("3847291055");
        assertThat(rejected.filledQty()).isEqualTo(Money.zero());
        assertThat(rejected.isOpen()).isFalse();
    }

    @Test
    void openMeansTheExchangeCouldStillChangeIt() {
        // Spelled out per status rather than derived from isTerminal(): restating the definition
        // would pass no matter what the definition became.
        assertThat(OrderStatus.values()).as("a new status must be given a meaning here too").hasSize(6);
        assertThat(opennessOf(OrderStatus.NEW)).isTrue();
        assertThat(opennessOf(OrderStatus.SUBMITTED)).isTrue();
        assertThat(opennessOf(OrderStatus.PARTIALLY_FILLED)).isTrue();
        assertThat(opennessOf(OrderStatus.FILLED)).isFalse();
        assertThat(opennessOf(OrderStatus.CANCELED)).isFalse();
        assertThat(opennessOf(OrderStatus.REJECTED)).isFalse();
    }

    private static boolean opennessOf(OrderStatus status) {
        return OrderRecord.ofNew(request(), T0).after(status, null, null, null, null, T0 + 1).isOpen();
    }
}
