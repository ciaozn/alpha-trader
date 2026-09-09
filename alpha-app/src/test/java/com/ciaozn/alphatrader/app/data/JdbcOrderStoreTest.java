package com.ciaozn.alphatrader.app.data;

import com.ciaozn.alphatrader.common.event.FillEvent;
import com.ciaozn.alphatrader.common.event.OrderRequestEvent;
import com.ciaozn.alphatrader.common.model.Money;
import com.ciaozn.alphatrader.common.model.OrderStatus;
import com.ciaozn.alphatrader.common.model.OrderType;
import com.ciaozn.alphatrader.common.model.Side;
import com.ciaozn.alphatrader.common.model.Symbol;
import com.ciaozn.alphatrader.execution.OrderRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The SQL half of the contract {@code InMemoryOrderStoreTest} pins in memory: same interface, same
 * invariants, asserted against a real SQLite file because the point of these statements is that they
 * also run on MySQL 8 (取舍 15 leaves the MySQL integration test to P4-8, so the neutrality here is
 * structural - no reserved words, no dialect upsert, no auto-increment).
 *
 * <p>Most of these assertions the in-memory store passes for free. The ones worth reading are the
 * three a {@code LinkedHashMap} cannot fail and a database can: scale surviving a column type
 * ({@code isEqualTo} on a BigDecimal is scale-sensitive, so the day a price column becomes NUMERIC or
 * DECIMAL these go red), NULL surviving as NULL rather than as a zero that reads like a price, and the
 * listing order the contract promises surviving the absence of any insertion order in SQL.
 */
class JdbcOrderStoreTest {

    private static final Symbol BTC = Symbol.parse("BTCUSDT.PERP");
    private static final Symbol ETH = Symbol.parse("ETHUSDT.PERP");
    private static final long T0 = 1_700_000_000_000L;

    @TempDir
    Path directory;

    private SQLiteDataSource dataSource() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + directory.resolve("business.db"));
        return dataSource;
    }

    private JdbcOrderStore store() {
        return new JdbcOrderStore(dataSource());
    }

    private static OrderRequestEvent market(String clientOrderId, Symbol symbol, String qty) {
        return OrderRequestEvent.of(clientOrderId, symbol, Side.BUY, OrderType.MARKET,
                new BigDecimal(qty), null, T0);
    }

    private static OrderRequestEvent limit(String clientOrderId, Symbol symbol, String qty, String price) {
        return OrderRequestEvent.of(clientOrderId, symbol, Side.SELL, OrderType.LIMIT,
                new BigDecimal(qty), new BigDecimal(price), T0);
    }

    private static FillEvent fill(String clientOrderId, Symbol symbol, String price, String qty,
                                  String fee, long businessTs) {
        return FillEvent.of(clientOrderId, symbol, Side.BUY, new BigDecimal(price), new BigDecimal(qty),
                new BigDecimal(fee), businessTs);
    }

    private static OrderRecord row(String clientOrderId, Symbol symbol, OrderStatus status, long createdAt) {
        OrderRecord created = OrderRecord.ofNew(market(clientOrderId, symbol, "0.1500"), createdAt);
        return status == OrderStatus.NEW
                ? created
                : created.after(status, "3847291055", null, null, null, createdAt + 1);
    }

    @Test
    void createsTheSixTablesOnFirstUseAndAgainOnTheSecond() throws SQLException {
        SQLiteDataSource dataSource = dataSource();
        new JdbcOrderStore(dataSource);

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet tables = statement.executeQuery(
                     "SELECT name FROM sqlite_master WHERE type = 'table'")) {
            List<String> names = new ArrayList<>();
            while (tables.next()) {
                names.add(tables.getString("name"));
            }
            // Exactly six: 取舍 15 fixed the count, and a seventh (an order_transitions history, say)
            // would be a second home for a fact the event journal already holds.
            assertThat(names).containsExactlyInAnyOrder("orders", "fills", "signals", "equity_snapshot",
                    "positions", "risk_interceptions");
        }

        // Constructing again over the same file must not fail: the schema step is idempotent, which is
        // what lets either store be constructed first and what makes a restart a no-op.
        assertThat(new JdbcOrderStore(dataSource).findOpen()).isEmpty();
    }

    @Test
    void anOrderComesBackFieldForFieldAcrossAReopenedStore() {
        OrderRecord written = OrderRecord.ofNew(limit("ma-cross-btc-1", BTC, "0.1500", "68000.10"), T0)
                .after(OrderStatus.PARTIALLY_FILLED, "3847291055", new BigDecimal("0.0500"),
                        new BigDecimal("68000.10"), "post-only order will not take liquidity", T0 + 5);

        store().save(written);

        // A fresh instance over the same file: the row is really on disk, not in a cache. equals on a
        // record compares every component, and BigDecimal.equals compares scale, so this one assertion
        // covers all thirteen columns and the exactness of the four decimal ones.
        assertThat(store().find("ma-cross-btc-1")).contains(written);
    }

    @Test
    void theColumnsTheExchangeSaidNothingAboutComeBackNullRatherThanZero() {
        store().save(OrderRecord.ofNew(market("ma-cross-btc-1", BTC, "0.1500"), T0));

        OrderRecord read = store().find("ma-cross-btc-1").orElseThrow();
        assertThat(read.exchangeOrderId()).as("a resting order has no id to ask the exchange about").isNull();
        assertThat(read.limitPrice()).as("a MARKET order was never priced").isNull();
        assertThat(read.avgFillPrice()).as("nothing has filled, and 0 would say it filled at zero").isNull();
        assertThat(read.statusMessage()).as("the exchange has not said anything yet").isNull();
        // The one decimal that is not nullable: a new order has filled nothing, which is a quantity.
        assertThat(read.filledQty()).isEqualTo(Money.zero());
    }

    @Test
    void pricesAndQuantitiesComeBackWithTheScaleTheyWentInWith() {
        JdbcOrderStore store = store();
        store.save(OrderRecord.ofNew(limit("ma-cross-btc-1", BTC, "0.1500", "68000.10"), T0)
                .after(OrderStatus.PARTIALLY_FILLED, "3847291055", new BigDecimal("0.0500"),
                        new BigDecimal("68000.10"), null, T0 + 5));
        store.saveFill(fill("ma-cross-btc-1", BTC, "68000.10", "0.0500", "0.05100000", T0 + 5));

        OrderRecord read = store().find("ma-cross-btc-1").orElseThrow();
        assertThat(read.qty()).isEqualTo(new BigDecimal("0.1500"));
        assertThat(read.limitPrice()).isEqualTo(new BigDecimal("68000.10"));
        assertThat(read.filledQty()).isEqualTo(new BigDecimal("0.0500"));
        assertThat(read.avgFillPrice()).isEqualTo(new BigDecimal("68000.10"));

        FillEvent stored = store().fills("ma-cross-btc-1").getFirst();
        assertThat(stored.price()).isEqualTo(new BigDecimal("68000.10"));
        assertThat(stored.qty()).isEqualTo(new BigDecimal("0.0500"));
        assertThat(stored.fee()).isEqualTo(new BigDecimal("0.05100000"));
    }

    @Test
    void savingTheSameOrderAgainReplacesTheRowAndLeavesItWhereItWas() {
        JdbcOrderStore store = store();
        OrderRecord first = row("ma-cross-btc-1", BTC, OrderStatus.NEW, T0);
        store.save(first);
        store.save(row("rsi-reversal-eth-2", ETH, OrderStatus.NEW, T0 + 1));

        // Migrated well after the second order arrived, as happens in a live run: if the listing were
        // ordered by updated_at, this is the moment the first order would move to the back.
        store.save(first.after(OrderStatus.SUBMITTED, "3847291055", null, null, null, T0 + 9));

        OrderRecord migrated = store.find("ma-cross-btc-1").orElseThrow();
        assertThat(migrated.status()).isEqualTo(OrderStatus.SUBMITTED);
        assertThat(migrated.createdAt()).as("a migration changes what the order is, not when it arrived")
                .isEqualTo(T0);
        assertThat(migrated.updatedAt()).isEqualTo(T0 + 9);
        assertThat(store.findOpen())
                .as("one row per clientOrderId, and a migration does not move it to the back")
                .extracting(OrderRecord::clientOrderId)
                .containsExactly("ma-cross-btc-1", "rsi-reversal-eth-2");

        store.save(migrated.after(OrderStatus.FILLED, null, new BigDecimal("0.1500"),
                new BigDecimal("68000.10"), null, T0 + 12));

        OrderRecord filled = store.find("ma-cross-btc-1").orElseThrow();
        assertThat(filled.status()).isEqualTo(OrderStatus.FILLED);
        assertThat(filled.avgFillPrice()).isEqualTo(new BigDecimal("68000.10"));
        assertThat(store.findOpen())
                .as("a filled order is one row that stopped being open, not a second row")
                .extracting(OrderRecord::clientOrderId)
                .containsExactly("rsi-reversal-eth-2");
    }

    @Test
    void anOrderSavedLaterWithAnEarlierCreatedAtStillListsLast() {
        // The reconciliation case (FR-EX-04): an order the exchange reports but this process never
        // held arrives carrying the exchange's own creation time, which is older than everything
        // already stored. Ordering the listing by created_at would put it at the front, so the listing
        // would reshuffle every time reconciliation ran - and reconciliation is what FR-EX-04 exists
        // to make trustworthy.
        JdbcOrderStore store = store();
        store.save(row("held-here-first", BTC, OrderStatus.SUBMITTED, T0 + 5_000));
        store.save(row("discovered-later", ETH, OrderStatus.SUBMITTED, T0));

        assertThat(store.findOpen())
                .extracting(OrderRecord::clientOrderId)
                .containsExactly("held-here-first", "discovered-later");
    }

    @Test
    void findOpenReturnsOnlyTheOrdersTheExchangeCouldStillChange() {
        JdbcOrderStore store = store();
        store.save(row("a-new", BTC, OrderStatus.NEW, T0));
        store.save(row("b-submitted", BTC, OrderStatus.SUBMITTED, T0 + 1));
        store.save(row("c-partial", BTC, OrderStatus.PARTIALLY_FILLED, T0 + 2));
        store.save(row("d-filled", BTC, OrderStatus.FILLED, T0 + 3));
        store.save(row("e-canceled", BTC, OrderStatus.CANCELED, T0 + 4));
        store.save(row("f-rejected", BTC, OrderStatus.REJECTED, T0 + 5));

        assertThat(store.findOpen())
                .extracting(OrderRecord::clientOrderId)
                .containsExactly("a-new", "b-submitted", "c-partial");
    }

    @Test
    void fillsStayWithTheirOwnOrderInTheOrderTheyWereStored() {
        // Timestamps and event ids both point the other way: the earlier fill was stored last, as
        // happens when a REST catch-up delivers what a WebSocket push missed. The store reports the
        // order it was told about them, because that is the only ordering which is a fact about this
        // system rather than about the exchange's clock.
        FillEvent earlier = fill("ma-cross-btc-1", BTC, "68000.10", "0.0500", "0.05100000", T0 + 1);
        FillEvent later = fill("ma-cross-btc-1", BTC, "68001.20", "0.1000", "0.10200000", T0 + 5);
        FillEvent other = fill("rsi-reversal-eth-2", ETH, "3500.05", "1.2000", "0.10500000", T0 + 9);
        JdbcOrderStore store = store();
        store.saveFill(later);
        store.saveFill(other);
        store.saveFill(earlier);

        assertThat(store.fills("ma-cross-btc-1")).containsExactly(later, earlier);
        assertThat(store.fills("rsi-reversal-eth-2")).containsExactly(other);
    }

    @Test
    void anOrderThisStoreNeverHeldIsAbsentRatherThanEmpty() {
        JdbcOrderStore store = store();

        // FR-EX-02's idempotency check needs a negative answer it can trust: "no row" has to mean
        // "never sent", not "cannot tell".
        assertThat(store.find("never-sent")).isEqualTo(Optional.empty());
        assertThat(store.fills("never-sent")).isEmpty();
        assertThat(store.findOpen()).isEmpty();
    }

    @Test
    void aReaderCannotWriteThroughTheListItWasHanded() {
        JdbcOrderStore store = store();
        store.save(row("a-new", BTC, OrderStatus.NEW, T0));
        store.saveFill(fill("a-new", BTC, "68000.10", "0.0500", "0.05100000", T0 + 1));

        List<OrderRecord> open = store.findOpen();
        List<FillEvent> fills = store.fills("a-new");
        assertThatThrownBy(() -> open.add(row("injected", BTC, OrderStatus.NEW, T0)))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> fills.add(fill("a-new", BTC, "1", "1", "0", T0)))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> fills.clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void twoLiveStoresOverOneFileDoNotClaimTheSamePositionTwice() {
        // Nothing in production builds two of these over one file, but the position counter is read
        // per insert rather than cached in a field precisely so that a second instance cannot hand out
        // a number the first one already used - a failure whose message would name a uniqueness
        // constraint and not its cause.
        SQLiteDataSource dataSource = dataSource();
        JdbcOrderStore first = new JdbcOrderStore(dataSource);
        JdbcOrderStore second = new JdbcOrderStore(dataSource);

        first.save(row("saved-by-the-first", BTC, OrderStatus.SUBMITTED, T0));
        second.save(row("saved-by-the-second", ETH, OrderStatus.SUBMITTED, T0 + 1));
        second.saveFill(fill("saved-by-the-first", BTC, "68000.10", "0.0500", "0.05100000", T0 + 2));
        first.saveFill(fill("saved-by-the-first", BTC, "68001.20", "0.1000", "0.10200000", T0 + 3));

        assertThat(first.findOpen()).extracting(OrderRecord::clientOrderId)
                .containsExactly("saved-by-the-first", "saved-by-the-second");
        assertThat(first.fills("saved-by-the-first"))
                .extracting(FillEvent::timestamp)
                .containsExactly(T0 + 2, T0 + 3);
    }
}
