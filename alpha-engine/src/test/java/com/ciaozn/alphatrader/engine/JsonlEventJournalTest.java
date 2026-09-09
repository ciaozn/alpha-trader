package com.ciaozn.alphatrader.engine;

import com.ciaozn.alphatrader.common.event.Event;
import com.ciaozn.alphatrader.common.event.EventIds;
import com.ciaozn.alphatrader.common.event.KlineEvent;
import com.ciaozn.alphatrader.common.event.OrderReportEvent;
import com.ciaozn.alphatrader.common.event.SignalEvent;
import com.ciaozn.alphatrader.common.event.TimerEvent;
import com.ciaozn.alphatrader.common.model.Direction;
import com.ciaozn.alphatrader.common.model.Interval;
import com.ciaozn.alphatrader.common.model.Kline;
import com.ciaozn.alphatrader.common.model.OrderStatus;
import com.ciaozn.alphatrader.common.model.Symbol;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JsonlEventJournalTest {

    @TempDir
    Path tempDir;

    @Test
    void roundTripPreservesAllEventFields() {
        Path journalPath = tempDir.resolve("events.jsonl");
        EventIds.reset();

        Kline kline = new Kline(1000L,
                new BigDecimal("50000.1"), new BigDecimal("50100.0"),
                new BigDecimal("49900.0"), new BigDecimal("50050.5"),
                new BigDecimal("123.456"), 2000L);
        KlineEvent klineEvent = KlineEvent.of(Symbol.parse("BTCUSDT.PERP"), Interval.H1, kline, true, 2000L);
        SignalEvent signal = SignalEvent.of("ma-cross-1", Symbol.parse("BTCUSDT.PERP"),
                Direction.LONG, 1.0, "SMA10 crossed above SMA30", 2000L);
        TimerEvent timer = TimerEvent.of("reconcile", 3000L);

        try (JsonlEventJournal journal = new JsonlEventJournal(journalPath)) {
            journal.append(klineEvent);
            journal.append(signal);
            journal.append(timer);
        }

        try (JsonlEventJournal reader = new JsonlEventJournal(journalPath)) {
            List<Event> events = reader.readAll();
            assertThat(events).hasSize(3);
            assertThat(events.get(0)).isEqualTo(klineEvent);
            assertThat(events.get(1)).isEqualTo(signal);
            assertThat(events.get(2)).isEqualTo(timer);
            // sealed type is recovered, not just the base interface
            assertThat(events.get(0)).isInstanceOf(KlineEvent.class);
            assertThat(((KlineEvent) events.get(0)).kline().close())
                    .isEqualByComparingTo(new BigDecimal("50050.5"));
        }
    }

    @Test
    void anOrderReportSurvivesTheJournalInBothItsShapes() {
        // Registered as a subtype or not at all: an unregistered one does not degrade quietly, it
        // makes readAll throw, and a crash recovery that cannot read the journal cannot replay the
        // orders the journal was written to recover.
        Path journalPath = tempDir.resolve("orders.jsonl");
        EventIds.reset();

        OrderReportEvent ack = OrderReportEvent.of("ma-cross-btc-2000-1", "2639485123",
                OrderStatus.SUBMITTED, null, 2000L);
        OrderReportEvent trade = OrderReportEvent.ofTrade("ma-cross-btc-2000-1", "2639485123",
                new BigDecimal("0.1500"), new BigDecimal("68000.10"), new BigDecimal("0.0510"), 3000L);

        try (JsonlEventJournal journal = new JsonlEventJournal(journalPath)) {
            journal.append(ack);
            journal.append(trade);
        }

        try (JsonlEventJournal reader = new JsonlEventJournal(journalPath)) {
            List<Event> events = reader.readAll();
            assertThat(events).containsExactly(ack, trade);
            assertThat(events.getFirst()).isInstanceOf(OrderReportEvent.class);
            // The two shapes are told apart by a null, so the nulls have to come back as nulls: a
            // mapper that defaulted lastQty to zero would turn every ack into an execution.
            assertThat(((OrderReportEvent) events.get(0)).isTrade()).isFalse();
            assertThat(((OrderReportEvent) events.get(1)).isTrade()).isTrue();
            assertThat(((OrderReportEvent) events.get(1)).lastQty()).isEqualTo(new BigDecimal("0.1500"));
            assertThat(((OrderReportEvent) events.get(1)).fee()).isEqualTo(new BigDecimal("0.0510"));
            assertThat(((OrderReportEvent) events.get(0)).status()).isEqualTo(OrderStatus.SUBMITTED);
            assertThat(((OrderReportEvent) events.get(1)).status()).isNull();
        }
    }
}
