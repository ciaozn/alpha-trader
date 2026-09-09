package com.ciaozn.alphatrader.common.event;

import com.ciaozn.alphatrader.common.model.OrderStatus;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonTypeName;

import java.math.BigDecimal;

/**
 * What the exchange said about one order (FR-EX-01, FR-EX-03). Published by whichever component
 * actually talked to the exchange - the user data stream parser, the sender that called
 * {@code placeOrder}, reconciliation after a REST query - and consumed by the OMS alone.
 *
 * <p><b>This is deliberately not {@code OrderUpdateEvent}.</b> That one is the OMS's outbound fact,
 * one per migration it accepted; using the same type inbound would have the OMS receiving its own
 * publications and reading each as a fresh report about the order. Two types is what keeps
 * "the exchange told me X" and "the system now knows X" distinguishable in the journal, which is
 * where a duplicated or reordered report has to be diagnosable after the fact.
 *
 * <p><b>Two shapes over one record, because that is what one exchange message is.</b> A lifecycle
 * report carries a {@link #status()} and nothing traded. A trade report carries what traded and no
 * status, because whether the order is now PARTIALLY_FILLED or FILLED is a fact about the order's
 * own quantity - the OMS derives it from the row rather than trusting a field that could disagree
 * with the numbers beside it. {@link #isTrade()} tells the two apart.
 *
 * <p><b>The quantities are the last execution's, not cumulative.</b> The OMS keeps the running
 * filled quantity and average price on the order row, so a lost report makes the row drift, and
 * FR-EX-04's reconciliation is the component whose job that is. Carrying the exchange's cumulative
 * figures here as well would give the same number two sources and turn every disagreement into a
 * judgement call at the point least able to make one.
 */
@JsonTypeName("orderReport")
public record OrderReportEvent(
        long eventId,
        long timestamp,
        String clientOrderId,
        String exchangeOrderId,
        OrderStatus status,
        BigDecimal lastQty,
        BigDecimal lastPrice,
        BigDecimal fee,
        String message) implements Event {

    /** An acknowledgment, a cancellation or a rejection: the order moved and nothing traded. */
    public static OrderReportEvent of(String clientOrderId, String exchangeOrderId, OrderStatus status,
                                      String message, long businessTs) {
        return new OrderReportEvent(EventIds.next(), businessTs, clientOrderId, exchangeOrderId,
                status, null, null, null, message);
    }

    /** One execution of {@code lastQty} at {@code lastPrice}, costing {@code fee}. */
    public static OrderReportEvent ofTrade(String clientOrderId, String exchangeOrderId,
                                           BigDecimal lastQty, BigDecimal lastPrice, BigDecimal fee,
                                           long businessTs) {
        return new OrderReportEvent(EventIds.next(), businessTs, clientOrderId, exchangeOrderId,
                null, lastQty, lastPrice, fee, null);
    }

    /**
     * Jackson would otherwise write this as a property named {@code trade} and then refuse to read the
     * line back: a record is rebuilt from its canonical components alone.
     */
    @JsonIgnore
    public boolean isTrade() {
        return lastQty != null;
    }
}
