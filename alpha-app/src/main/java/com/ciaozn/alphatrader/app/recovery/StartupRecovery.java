package com.ciaozn.alphatrader.app.recovery;

import com.ciaozn.alphatrader.common.event.Event;
import com.ciaozn.alphatrader.common.event.RiskAlertEvent;
import com.ciaozn.alphatrader.common.model.Symbol;
import com.ciaozn.alphatrader.common.portfolio.Portfolio;
import com.ciaozn.alphatrader.common.time.Clock;
import com.ciaozn.alphatrader.engine.EventEngine;
import com.ciaozn.alphatrader.engine.EventJournal;
import com.ciaozn.alphatrader.engine.JournalReplay;
import com.ciaozn.alphatrader.execution.OrderStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Startup recovery (T403, FR-EN-05, spec edge case 7): after the exchange's full snapshot has been
 * applied to the local book, replay the event journal and alert on anything the two disagree about -
 * <b>without changing either</b>.
 *
 * <p><b>The order it runs in is the whole guarantee.</b> {@code StartupWiring} calls
 * {@link ReconciliationRunner} first and this second, both as loop tasks, and the engine drains its
 * task queue before it polls for the next external event. So by the time a strategy could see the
 * first bar: the exchange's orders/positions/account have been synced into the book (T318), and this
 * comparison has run against that corrected book. Nothing here needs a timer or a lock to hold that
 * order - it is a property of the single-threaded loop, which is exactly why the recovery is a loop
 * task and not a runner with its own thread.
 *
 * <p><b>Alert, never repair.</b> A disagreement means the log and the exchange tell different stories,
 * and neither is obviously right: the journal can be missing a fill the exchange knows about, or the
 * book can have been moved by a correction we did not log. Rewriting either from here would silently
 * choose a winner, and a silent correction is indistinguishable from the bug it papered over.
 * Reconciliation (T318) is the component that owns changing the book, and it corrects towards the
 * exchange; this class only makes the difference visible, as a CRITICAL {@link RiskAlertEvent}.
 *
 * <p><b>Two comparisons, both read-only.</b>
 * <ol>
 *   <li>the net position each symbol reaches by replaying the journal, against the book after the
 *       exchange sync - a position the exchange holds that the log never produced, or one the log
 *       produced that the exchange no longer holds;</li>
 *   <li>every order the log says was requested, against the {@code orders} table - a request in the
 *       journal with no row means the process died between the journal write and the OMS write, which
 *       is the exact window FR-EN-02's write-ahead journal exists to make visible.</li>
 * </ol>
 *
 * <p>Runs with the book's usual confinement: {@link #verify(List)} touches {@link Portfolio} and the
 * order store, so it is only ever called on the engine thread. {@link #start()} reads the journal on
 * the calling thread (file IO does not belong on the loop) and hands the comparison over.
 */
public final class StartupRecovery {

    /** The journal's positions and the book's disagree. Alert only; T318 owns the correction. */
    public static final String RULE_POSITION_MISMATCH = "EN-recovery-position-mismatch";
    /** The journal requested an order the orders table has no row for. */
    public static final String RULE_ORDER_MISSING = "EN-recovery-order-missing";

    private static final Logger log = LoggerFactory.getLogger(StartupRecovery.class);

    private final EventJournal journal;
    private final Portfolio portfolio;
    private final OrderStore orders;
    private final Clock clock;
    private final EventEngine engine;

    public StartupRecovery(EventJournal journal, Portfolio portfolio, OrderStore orders, Clock clock,
                           EventEngine engine) {
        this.journal = journal;
        this.portfolio = portfolio;
        this.orders = orders;
        this.clock = clock;
        this.engine = engine;
    }

    /**
     * Reads the journal and hands the verification to the loop thread, then gets out of the way.
     * Called once by {@code StartupWiring}, after the exchange sync and before market data can be
     * dispatched. The journal read happens here rather than on the loop because it is file IO; the
     * comparison happens there because it reads the book.
     */
    public void start() {
        List<Event> journaled = journal.readAll();
        engine.runOnLoop(() -> {
            List<RiskAlertEvent> alerts = verify(journaled);
            for (RiskAlertEvent alert : alerts) {
                log.error("Startup recovery found an inconsistency [{}]: {}", alert.ruleId(), alert.detail());
                engine.publish(alert);
            }
            log.info("Startup recovery verified {} journaled event(s): {} inconsistency(ies)",
                    journaled.size(), alerts.size());
        });
    }

    /**
     * The comparison, with no side effects: it returns the alerts the caller must publish and leaves
     * the book and the order store exactly as it found them. Public so the recovery can be tested -
     * and its "nothing was changed" property asserted - without a running engine.
     *
     * <p>An empty journal is not a failure and not a finding: it is a process that has not traded since
     * its log began, and the honest answer is that there is nothing to verify. That also keeps a fresh
     * start - and the container tests, whose journals are empty - free of noise.
     */
    public List<RiskAlertEvent> verify(List<Event> journaled) {
        JournalReplay.ReplayedState expected = JournalReplay.replay(journaled);
        if (expected.events() == 0) {
            return List.of();
        }
        long now = clock.nowMillis();
        List<RiskAlertEvent> alerts = new ArrayList<>();

        // Positions: the union of what each side knows about, so a symbol present on only one side is
        // compared against the zero the other side implies rather than skipped.
        Set<Symbol> symbols = new LinkedHashSet<>(expected.signedQtyBySymbol().keySet());
        portfolio.openPositions().forEach(position -> symbols.add(position.symbol()));
        for (Symbol symbol : symbols) {
            BigDecimal logged = expected.signedQtyBySymbol().getOrDefault(symbol, BigDecimal.ZERO);
            BigDecimal held = portfolio.position(symbol).signedQty();
            if (logged.compareTo(held) != 0) {
                alerts.add(RiskAlertEvent.of(RULE_POSITION_MISMATCH, RiskAlertEvent.Severity.CRITICAL,
                        "journal and book disagree on " + symbol.unified() + ": journal implies "
                                + logged.toPlainString() + ", book holds " + held.toPlainString()
                                + " (journal through event " + expected.lastEventId()
                                + "); not corrected here - reconciliation owns the book", now));
            }
        }

        // Orders: a request the journal recorded but the orders table never saw. The engine journals
        // before dispatch (FR-EN-02), so this is the signature of a death between the two writes.
        for (String clientOrderId : expected.requestedClientOrderIds()) {
            if (orders.find(clientOrderId).isEmpty()) {
                alerts.add(RiskAlertEvent.of(RULE_ORDER_MISSING, RiskAlertEvent.Severity.CRITICAL,
                        "journal requested order " + clientOrderId + " but the orders table has no row for"
                                + " it (crash between the journal write and the OMS write); the request is"
                                + " not re-driven here - reconciliation decides what the exchange holds",
                        now));
            }
        }
        return List.copyOf(alerts);
    }
}
