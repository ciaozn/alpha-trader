package com.ciaozn.alphatrader.app.recovery;

import com.ciaozn.alphatrader.common.event.Event;
import com.ciaozn.alphatrader.common.event.FillEvent;
import com.ciaozn.alphatrader.common.event.OrderRequestEvent;
import com.ciaozn.alphatrader.common.event.RiskAlertEvent;
import com.ciaozn.alphatrader.common.model.OrderType;
import com.ciaozn.alphatrader.common.model.Side;
import com.ciaozn.alphatrader.common.model.Symbol;
import com.ciaozn.alphatrader.common.portfolio.Portfolio;
import com.ciaozn.alphatrader.common.time.VirtualClock;
import com.ciaozn.alphatrader.engine.EventEngine;
import com.ciaozn.alphatrader.engine.EventJournal;
import com.ciaozn.alphatrader.execution.InMemoryOrderStore;
import com.ciaozn.alphatrader.execution.OrderRecord;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T403: the recovery comparison. The journal is hand-written and the book is set up to disagree with
 * it, because the interesting behaviour is all in the disagreement - and a test that had to cause a
 * real crash to reach it would not be worth running.
 *
 * <p>Every case also asserts the property the spec is explicit about: <b>the recovery changes
 * nothing.</b> A difference is reported, never repaired - the repair is reconciliation's (T318), and a
 * silent correction here would hide the very divergence that made the test worth writing.
 */
class StartupRecoveryTest {

    private static final Symbol BTC = Symbol.parse("BTCUSDT.PERP");
    private static final Symbol ETH = Symbol.parse("ETHUSDT.PERP");
    private static final long T0 = 1_700_000_000_000L;

    private final Portfolio portfolio = new Portfolio(new BigDecimal("10000"));
    private final InMemoryOrderStore orders = new InMemoryOrderStore();
    private final VirtualClock clock = new VirtualClock(T0);
    private final StartupRecovery recovery = new StartupRecovery(EventJournal.noop(), portfolio, orders,
            clock, new EventEngine(EventJournal.noop(), clock));

    private static FillEvent fill(long id, Symbol symbol, Side side, String price, String qty) {
        return new FillEvent(id, T0, "o" + id, symbol, side, new BigDecimal(price), new BigDecimal(qty),
                BigDecimal.ZERO);
    }

    private static OrderRequestEvent request(long id, String clientOrderId) {
        return new OrderRequestEvent(id, T0, clientOrderId, BTC, Side.BUY, OrderType.MARKET,
                new BigDecimal("0.5"), null);
    }

    private static List<RiskAlertEvent> alertsOfRule(List<RiskAlertEvent> alerts, String ruleId) {
        return alerts.stream().filter(alert -> alert.ruleId().equals(ruleId)).toList();
    }

    @Test
    void aPositionTheJournalImpliesButTheBookDoesNotHoldIsAlertedAndNothingIsChanged() {
        List<Event> journal = List.of(fill(1, BTC, Side.BUY, "100", "0.5"));

        List<RiskAlertEvent> alerts = recovery.verify(journal);

        assertThat(alerts).hasSize(1);
        RiskAlertEvent alert = alerts.getFirst();
        assertThat(alert.ruleId()).isEqualTo(StartupRecovery.RULE_POSITION_MISMATCH);
        assertThat(alert.severity()).isEqualTo(RiskAlertEvent.Severity.CRITICAL);
        assertThat(alert.detail()).contains("BTCUSDT.PERP").contains("0.5").contains("holds 0");
        // The whole point: the book was not quietly rewritten to match the journal.
        assertThat(portfolio.position(BTC).isEmpty()).isTrue();
        assertThat(portfolio.openPositions()).isEmpty();
    }

    @Test
    void aPositionTheBookHoldsButTheJournalNeverProducedIsAlerted() {
        // A fresh book carrying a position (the exchange sync put it there) with a journal that only
        // ever saw an ETH fill: neither symbol agrees, and both are reported.
        portfolio.applyFill(BTC, Side.BUY, new BigDecimal("100"), BigDecimal.ONE, BigDecimal.ZERO);
        List<Event> journal = List.of(fill(1, ETH, Side.BUY, "50", "1"));

        List<RiskAlertEvent> alerts = recovery.verify(journal);

        assertThat(alertsOfRule(alerts, StartupRecovery.RULE_POSITION_MISMATCH)).hasSize(2);
        // And the book still holds exactly what it held before the check.
        assertThat(portfolio.position(BTC).signedQty()).isEqualByComparingTo("1");
        assertThat(portfolio.position(ETH).isEmpty()).isTrue();
    }

    @Test
    void anOrderTheJournalRequestedButTheTableNeverRecordedIsAlerted() {
        List<Event> journal = List.of(request(1, "ma-1-abc"));

        List<RiskAlertEvent> alerts = recovery.verify(journal);

        assertThat(alertsOfRule(alerts, StartupRecovery.RULE_ORDER_MISSING)).hasSize(1);
        assertThat(alerts.getFirst().detail()).contains("ma-1-abc");
        // Reported, not re-driven: the order is not invented into the store either.
        assertThat(orders.find("ma-1-abc")).isEmpty();
        assertThat(orders.findOpen()).isEmpty();
    }

    @Test
    void aJournalThatAgreesWithTheBookAndTheTableProducesNothing() {
        portfolio.applyFill(BTC, Side.BUY, new BigDecimal("100"), new BigDecimal("0.5"), BigDecimal.ZERO);
        OrderRequestEvent order = request(1, "ma-1-abc");
        orders.save(OrderRecord.ofNew(order, T0));
        List<Event> journal = List.of(order, fill(2, BTC, Side.BUY, "100", "0.5"));

        assertThat(recovery.verify(journal)).isEmpty();
    }

    @Test
    void anEmptyJournalIsNotAFinding() {
        // A process with no journaled events has nothing to verify, even if the exchange sync has
        // already put a position in the book. Reporting that as a divergence would make every restart
        // after a quiet day look like a corruption.
        portfolio.applyFill(BTC, Side.BUY, new BigDecimal("100"), BigDecimal.ONE, BigDecimal.ZERO);

        assertThat(recovery.verify(List.of())).isEmpty();
    }
}
