package com.ciaozn.alphatrader.engine;

import com.ciaozn.alphatrader.common.event.Event;
import com.ciaozn.alphatrader.common.event.FillEvent;
import com.ciaozn.alphatrader.common.event.OrderRequestEvent;
import com.ciaozn.alphatrader.common.model.Symbol;
import com.ciaozn.alphatrader.common.portfolio.Portfolio;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Folds an event journal back into the state it implies (T403, FR-EN-05): the net position of every
 * symbol and the set of orders the log says were requested.
 *
 * <p><b>This is the "expected state" half of crash recovery, and it is deliberately pure.</b> It takes a
 * list it did not produce and answers with a value; it reads no file, touches no store and knows nothing
 * about the live book. That is what lets the recovery comparison be tested against a hand-written
 * journal - the interesting cases are all disagreements, and constructing those against a running
 * engine would mean a test that has to cause a crash to say anything.
 *
 * <p><b>Why a fresh book rather than the live one.</b> The positions have to be derived from the fills
 * alone, because the live {@link Portfolio} may already have been moved by the exchange sync that ran
 * before this (T318). Replaying into the live book would make the comparison compare the book to itself.
 * A zero-equity scratch book holds the position arithmetic - the same {@link Portfolio#applyFill} the
 * live path uses, so the two cannot drift (FR-BT-06) - and nothing else it writes is read.
 *
 * <p>What it does not do: it does not decide what a difference means, and it does not repair anything.
 * A replay that could "fix" the book would be a second writer racing reconciliation; the comparison and
 * the alert belong to the caller, and the repair belongs to T318.
 */
public final class JournalReplay {

    private JournalReplay() {
    }

    /**
     * The state a journal implies. Positions are the net quantity per symbol after every fill, in
     * first-touched order; {@code requestedClientOrderIds} is every order the log says was sent,
     * whether or not it ever filled. {@code lastEventId} is the log's high-water mark, carried so a
     * difference report can say how far the replay got.
     */
    public record ReplayedState(
            Map<Symbol, BigDecimal> signedQtyBySymbol,
            Map<Symbol, BigDecimal> entryPriceBySymbol,
            Set<String> requestedClientOrderIds,
            long lastEventId,
            long lastTimestamp,
            int events) {

        public ReplayedState {
            // Linked copies, not Map/Set.copyOf: those return hash-ordered immutables, and a diff
            // report whose order changes run to run is unreadable (NFR-04).
            signedQtyBySymbol = Collections.unmodifiableMap(new LinkedHashMap<>(signedQtyBySymbol));
            entryPriceBySymbol = Collections.unmodifiableMap(new LinkedHashMap<>(entryPriceBySymbol));
            requestedClientOrderIds = Collections.unmodifiableSet(new LinkedHashSet<>(requestedClientOrderIds));
        }

        /** The replay of an empty (or absent) journal: nothing was ever traded. */
        public static ReplayedState empty() {
            return new ReplayedState(Map.of(), Map.of(), Set.of(), 0L, 0L, 0);
        }
    }

    /**
     * Replays {@code events} in the order given. The order matters and is the caller's to preserve:
     * the journal is FIFO by construction, and folding fills out of order would produce a different
     * entry price and, for a flip, a different realized result.
     */
    public static ReplayedState replay(List<Event> events) {
        if (events == null || events.isEmpty()) {
            return ReplayedState.empty();
        }
        Portfolio scratch = new Portfolio(BigDecimal.ZERO);
        // Linked* so the result follows the journal rather than a hash, matching the store contracts
        // (NFR-04): a diff report that reordered itself run to run would be unreadable.
        Set<String> requested = new LinkedHashSet<>();
        long lastEventId = 0L;
        long lastTimestamp = 0L;

        for (Event event : events) {
            lastEventId = Math.max(lastEventId, event.eventId());
            lastTimestamp = Math.max(lastTimestamp, event.timestamp());
            switch (event) {
                case FillEvent fill -> scratch.applyFill(fill.symbol(), fill.side(), fill.price(),
                        fill.qty(), fill.fee());
                case OrderRequestEvent request -> requested.add(request.clientOrderId());
                default -> {
                    // K-lines, signals, reports, alerts and timers imply no position and no order of
                    // their own; an OrderReportEvent moves an order's state, not its existence, and is
                    // covered by the OrderRequestEvent that opened it.
                }
            }
        }

        Map<Symbol, BigDecimal> qty = new LinkedHashMap<>();
        Map<Symbol, BigDecimal> entry = new LinkedHashMap<>();
        for (var position : scratch.openPositions()) {
            qty.put(position.symbol(), position.signedQty());
            entry.put(position.symbol(), position.entryPrice());
        }
        return new ReplayedState(qty, entry, requested, lastEventId, lastTimestamp, events.size());
    }
}
