package com.ciaozn.alphatrader.engine;

import com.ciaozn.alphatrader.common.event.Event;
import com.ciaozn.alphatrader.common.event.FillEvent;
import com.ciaozn.alphatrader.common.event.OrderRequestEvent;
import com.ciaozn.alphatrader.common.model.OrderType;
import com.ciaozn.alphatrader.common.model.Side;
import com.ciaozn.alphatrader.common.model.Symbol;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T403: the pure half of crash recovery - what the journal says happened. The comparison against the
 * live book is {@code StartupRecoveryTest}'s; here the journal is the only input, which is what makes
 * the arithmetic checkable without an engine or a store.
 */
class JournalReplayTest {

    private static final Symbol BTC = Symbol.parse("BTCUSDT.PERP");
    private static final Symbol ETH = Symbol.parse("ETHUSDT.PERP");
    private static final long T0 = 1_700_000_000_000L;

    private static FillEvent fill(long id, long ts, Symbol symbol, Side side, String price, String qty) {
        return new FillEvent(id, ts, "o" + id, symbol, side, new BigDecimal(price), new BigDecimal(qty),
                BigDecimal.ZERO);
    }

    @Test
    void foldsFillsIntoNetSignedPositions() {
        JournalReplay.ReplayedState state = JournalReplay.replay(List.of(
                fill(1, T0, BTC, Side.BUY, "100", "2"),
                fill(2, T0 + 1, BTC, Side.SELL, "110", "1")));

        assertThat(state.signedQtyBySymbol()).containsOnlyKeys(BTC);
        assertThat(state.signedQtyBySymbol().get(BTC)).isEqualByComparingTo("1");
        // A reduce leaves the entry price alone; only the closed part realizes.
        assertThat(state.entryPriceBySymbol().get(BTC)).isEqualByComparingTo("100");
    }

    @Test
    void aFillBeyondZeroReversesTheDirectionAtTheFillPrice() {
        JournalReplay.ReplayedState state = JournalReplay.replay(List.of(
                fill(1, T0, BTC, Side.BUY, "100", "1"),
                fill(2, T0 + 1, BTC, Side.SELL, "110", "3")));

        assertThat(state.signedQtyBySymbol().get(BTC)).isEqualByComparingTo("-2");
        assertThat(state.entryPriceBySymbol().get(BTC)).isEqualByComparingTo("110");
    }

    @Test
    void tracksEachSymbolSeparately() {
        JournalReplay.ReplayedState state = JournalReplay.replay(List.of(
                fill(1, T0, BTC, Side.BUY, "100", "2"),
                fill(2, T0, ETH, Side.SELL, "50", "3")));

        assertThat(state.signedQtyBySymbol()).containsOnlyKeys(BTC, ETH);
        assertThat(state.signedQtyBySymbol().get(BTC)).isEqualByComparingTo("2");
        assertThat(state.signedQtyBySymbol().get(ETH)).isEqualByComparingTo("-3");
    }

    @Test
    void recordsEveryRequestedOrderAndTheJournalsWatermark() {
        JournalReplay.ReplayedState state = JournalReplay.replay(List.of(
                new OrderRequestEvent(3, T0, "ma-1", BTC, Side.BUY, OrderType.MARKET,
                        new BigDecimal("0.5"), null),
                fill(5, T0 + 10, BTC, Side.BUY, "100", "0.5"),
                new OrderRequestEvent(8, T0 + 20, "ma-2", BTC, Side.SELL, OrderType.MARKET,
                        new BigDecimal("0.5"), null)));

        // Every request counts, whether or not it ever filled: the recovery's order check is "the log
        // says we sent it, the table must know about it".
        assertThat(state.requestedClientOrderIds()).containsExactly("ma-1", "ma-2");
        assertThat(state.lastEventId()).isEqualTo(8);
        assertThat(state.lastTimestamp()).isEqualTo(T0 + 20);
        assertThat(state.events()).isEqualTo(3);
    }

    @Test
    void anEmptyJournalImpliesNothingRatherThanFailing() {
        assertThat(JournalReplay.replay(List.of())).isEqualTo(JournalReplay.ReplayedState.empty());
        assertThat(JournalReplay.replay(null)).isEqualTo(JournalReplay.ReplayedState.empty());
    }

    @Test
    void eventsThatAreNotFillsOrRequestsMoveNoMoney() {
        JournalReplay.ReplayedState state = JournalReplay.replay(List.of(
                com.ciaozn.alphatrader.common.event.RiskAlertEvent.of("RK-x",
                        com.ciaozn.alphatrader.common.event.RiskAlertEvent.Severity.INFO, "noise", T0)));

        assertThat(state.signedQtyBySymbol()).isEmpty();
        assertThat(state.requestedClientOrderIds()).isEmpty();
        assertThat(state.lastEventId()).isPositive();
    }
}
