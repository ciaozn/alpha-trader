package com.ciaozn.alphatrader.execution;

import com.ciaozn.alphatrader.common.event.OrderRequestEvent;
import com.ciaozn.alphatrader.common.model.Money;
import com.ciaozn.alphatrader.common.model.OrderStatus;
import com.ciaozn.alphatrader.common.model.OrderType;
import com.ciaozn.alphatrader.common.model.Side;
import com.ciaozn.alphatrader.common.model.Symbol;

import java.math.BigDecimal;

/**
 * One row of the {@code orders} table: an order's <em>current state</em>, not one event in its life
 * (FR-EX-01, FR-OP-04).
 *
 * <p><b>"每次迁移落库" means this row is rewritten on every migration, not that a migration gets a
 * row of its own.</b> The migration history already has a home: one {@code OrderUpdateEvent} per
 * transition in the append-only event journal (DESIGN §11), which is also the crash-recovery and
 * replay source. A seventh {@code order_transitions} table would give the same sequence a second
 * home that can disagree with the first, and 取舍 15 fixed the schema at six tables.
 *
 * <p><b>This record is state, not policy.</b> It does not know which migrations are legal and
 * {@link #after} will happily produce a row that goes FILLED &rarr; SUBMITTED. The lifecycle rules
 * and the refusal of illegal migrations belong to the OMS, where they can be tested against the
 * exchange's behaviour; a row that enforced them too would put the state machine in two places, and
 * the copy nobody exercises is the one that is wrong.
 *
 * <p><b>The nulls are the record's information, so they are typed rather than defaulted.</b>
 * {@code exchangeOrderId} is null until the exchange acknowledges, {@code limitPrice} is null for a
 * MARKET order, {@code avgFillPrice} is null until the first fill, {@code statusMessage} is null
 * unless the exchange explained itself. Zero in any of those columns would be a lie of a kind that
 * reads as data: an order filled at price 0, or one resting with no id the exchange can be asked
 * about.
 */
public record OrderRecord(
        String clientOrderId,
        String exchangeOrderId,
        Symbol symbol,
        Side side,
        OrderType orderType,
        BigDecimal qty,
        BigDecimal limitPrice,
        BigDecimal filledQty,
        BigDecimal avgFillPrice,
        OrderStatus status,
        String statusMessage,
        long createdAt,
        long updatedAt) {

    /**
     * The row an {@link OrderRequestEvent} opens: NEW, nothing filled, and nothing known that the
     * exchange has not yet said. {@code createdAt} and {@code updatedAt} start equal, which is what
     * makes "this order has never moved" readable off the row.
     */
    public static OrderRecord ofNew(OrderRequestEvent order, long now) {
        return new OrderRecord(order.clientOrderId(), null, order.symbol(), order.side(), order.orderType(),
                order.qty(), order.price(), Money.zero(), null, OrderStatus.NEW, null, now, now);
    }

    /**
     * The same order after one lifecycle event. A null argument means "the exchange said nothing
     * about this", so it keeps the value already held; {@code status} and {@code updatedAt} are
     * required because a migration that does not move them is not a migration.
     *
     * <p>{@code updatedAt} always advances: "when did this row last change" is the half of FR-EX-01
     * a status alone cannot answer, and reconciliation needs it to tell an order genuinely resting
     * on the book from one this process forgot about.
     */
    public OrderRecord after(OrderStatus status, String exchangeOrderId, BigDecimal filledQty,
                             BigDecimal avgFillPrice, String statusMessage, long updatedAt) {
        return new OrderRecord(clientOrderId,
                exchangeOrderId == null ? this.exchangeOrderId : exchangeOrderId,
                symbol, side, orderType, qty, limitPrice,
                filledQty == null ? this.filledQty : filledQty,
                avgFillPrice == null ? this.avgFillPrice : avgFillPrice,
                status,
                statusMessage == null ? this.statusMessage : statusMessage,
                createdAt, updatedAt);
    }

    /** Still something the exchange could change - so still something reconciliation must ask about. */
    public boolean isOpen() {
        return !status.isTerminal();
    }
}
