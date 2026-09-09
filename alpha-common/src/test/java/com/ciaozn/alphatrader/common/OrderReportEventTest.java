package com.ciaozn.alphatrader.common;

import com.ciaozn.alphatrader.common.event.OrderReportEvent;
import com.ciaozn.alphatrader.common.model.OrderStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T312: the two shapes of an exchange report, and the nulls that tell them apart.
 *
 * <p>Nine components and two factories is enough to transpose an argument, and the OMS is a poor
 * place to catch it: on a trade it reads neither {@code status} nor {@code message}, and on a
 * lifecycle report it reads neither {@code lastQty} nor {@code fee}, so a swap between the two groups
 * would travel all the way to the journal before anybody noticed. What the OMS does read is
 * {@link OrderReportEvent#isTrade()}, and that is a null test - so the nulls are asserted as
 * deliberately as the values.
 */
class OrderReportEventTest {

    private static final long T0 = 1_700_000_000_000L;

    @Test
    void aLifecycleReportCarriesAStatusAndNoExecution() {
        OrderReportEvent report = OrderReportEvent.of("ma-cross-btc-2000-1", "2639485123",
                OrderStatus.CANCELED, "client canceled", T0);

        assertThat(report.isTrade()).isFalse();
        assertThat(report.clientOrderId()).isEqualTo("ma-cross-btc-2000-1");
        assertThat(report.exchangeOrderId()).isEqualTo("2639485123");
        assertThat(report.status()).isEqualTo(OrderStatus.CANCELED);
        assertThat(report.message()).isEqualTo("client canceled");
        assertThat(report.timestamp()).isEqualTo(T0);
        assertThat(report.eventId()).isPositive();
        assertThat(report.lastQty()).isNull();
        assertThat(report.lastPrice()).isNull();
        assertThat(report.fee()).isNull();
    }

    @Test
    void aTradeReportCarriesAnExecutionAndNoStatus() {
        OrderReportEvent report = OrderReportEvent.ofTrade("ma-cross-btc-2000-1", "2639485123",
                new BigDecimal("0.1500"), new BigDecimal("68000.10"), new BigDecimal("0.0510"), T0);

        assertThat(report.isTrade()).isTrue();
        assertThat(report.clientOrderId()).isEqualTo("ma-cross-btc-2000-1");
        assertThat(report.exchangeOrderId()).isEqualTo("2639485123");
        assertThat(report.lastQty()).isEqualTo(new BigDecimal("0.1500"));
        assertThat(report.lastPrice()).isEqualTo(new BigDecimal("68000.10"));
        assertThat(report.fee()).isEqualTo(new BigDecimal("0.0510"));
        assertThat(report.timestamp()).isEqualTo(T0);
        assertThat(report.eventId()).isPositive();
        // No status, because whether the order is now partially or fully filled follows from the
        // order's own quantity, which only the OMS knows. A status here could disagree with the
        // quantities beside it, and then one of the two would have to be discarded.
        assertThat(report.status()).isNull();
        assertThat(report.message()).isNull();
    }

    @Test
    void aTradeIsATradeWhateverTheExchangeSaidAboutTheFee() {
        // The shape is decided by lastQty alone - by the fee's value and by its presence both. Deciding
        // it on the fee as well would make a zero-fee execution (a maker rebate, a fee-free symbol) or
        // one whose commission field never arrived come back as a lifecycle report with no status, and
        // the OMS would then refuse it as a report claiming a fill it never made.
        OrderReportEvent free = OrderReportEvent.ofTrade("cid", "ex", new BigDecimal("0.1500"),
                new BigDecimal("68000.10"), BigDecimal.ZERO, T0);
        OrderReportEvent noFeeReported = OrderReportEvent.ofTrade("cid", "ex", new BigDecimal("0.1500"),
                new BigDecimal("68000.10"), null, T0);

        assertThat(free.isTrade()).isTrue();
        assertThat(free.fee()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(noFeeReported.isTrade()).as("a missing commission is not a missing execution").isTrue();
        assertThat(noFeeReported.fee()).isNull();
        assertThat(noFeeReported.status()).isNull();
    }
}
