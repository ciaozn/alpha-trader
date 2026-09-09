package com.ciaozn.alphatrader.backtest.feed;

import com.ciaozn.alphatrader.common.data.KlineRepository;
import com.ciaozn.alphatrader.common.event.KlineEvent;
import com.ciaozn.alphatrader.common.event.SignalEvent;
import com.ciaozn.alphatrader.common.model.Direction;
import com.ciaozn.alphatrader.common.model.Interval;
import com.ciaozn.alphatrader.common.model.Kline;
import com.ciaozn.alphatrader.common.model.Symbol;
import com.ciaozn.alphatrader.common.time.VirtualClock;
import com.ciaozn.alphatrader.engine.EventEngine;
import com.ciaozn.alphatrader.engine.EventJournal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BacktestDataFeederTest {

    private static final Symbol BTC = Symbol.parse("BTCUSDT.PERP");
    private static final Symbol ETH = Symbol.parse("ETHUSDT.PERP");
    private static final long T0 = 1_700_000_000_000L;
    private static final long HOUR = 3_600_000L;

    private final List<EventEngine> engines = new ArrayList<>();
    private final MapKlineRepository repository = new MapKlineRepository();
    private final List<Seen> seen = new CopyOnWriteArrayList<>();

    /** What a handler observed: the event's business time and what the clock read. */
    private record Seen(String symbol, long businessTs, long clockNow, boolean closed) {

        static Seen of(KlineEvent event, long clockNow) {
            return new Seen(event.symbol().unified(), event.timestamp(), clockNow, event.closed());
        }
    }

    @AfterEach
    void tearDown() {
        // Every engine owns a non-daemon thread: stopping only the last one would leak the
        // rest and hang the surefire fork after the tests had already passed.
        engines.forEach(EventEngine::stop);
    }

    private VirtualClock clock = new VirtualClock(T0);

    /** Between two runs inside one test: stop the old engine and start the clock over. */
    private void freshRun() {
        engines.forEach(EventEngine::stop);
        engines.clear();
        seen.clear();
        clock = new VirtualClock(T0);
    }

    private EventEngine newEngine(VirtualClock replayClock) {
        EventEngine created = new EventEngine(EventJournal.noop(), replayClock);
        engines.add(created);
        return created;
    }

    /** Starts an engine that records every k-line it is handed, plus the clock it reads. */
    private BacktestDataFeeder feeder(Duration timeout) {
        VirtualClock replayClock = clock;
        EventEngine created = newEngine(replayClock);
        created.registerHandler((event, publisher) -> {
            if (event instanceof KlineEvent kline) {
                seen.add(Seen.of(kline, replayClock.nowMillis()));
            }
        });
        created.start();
        return new BacktestDataFeeder(created, replayClock, timeout);
    }

    private static Kline bar(long openTime, String price) {
        BigDecimal value = new BigDecimal(price);
        return new Kline(openTime, value, value.add(BigDecimal.ONE), value.subtract(BigDecimal.ONE),
                value, BigDecimal.ONE, openTime + HOUR - 1);
    }

    private static List<Kline> bars(long from, int count, String firstPrice) {
        List<Kline> klines = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            klines.add(bar(from + index * HOUR, firstPrice + index));
        }
        return klines;
    }

    private static BacktestDataFeeder.Series series(Symbol symbol) {
        return new BacktestDataFeeder.Series(symbol, Interval.H1);
    }

    @Test
    void replaysEveryBarInBusinessTimeOrderWithTheClockOnTheBarsClose() {
        repository.put(BTC, Interval.H1, bars(T0, 3, "30000."));
        repository.put(ETH, Interval.H1, bars(T0, 3, "2000."));
        BacktestDataFeeder feeder = feeder(Duration.ofSeconds(5));

        BacktestDataFeeder.ReplaySummary summary =
                feeder.replay(repository, List.of(series(BTC), series(ETH)), T0, T0 + 2 * HOUR);

        // clockNow == businessTs on every bar: handlers must read this bar's close, never the
        // previous bar's. The advance-before-publish ordering that guarantees it is argued in
        // advanceAndPublish rather than asserted here - reversing those two adjacent lines is
        // a race that still reads correctly almost every time, so no timing test catches it.
        // interleaved by time, never one symbol's whole history followed by the other's
        assertThat(seen).containsExactly(
                new Seen("BTCUSDT.PERP", T0 + HOUR - 1, T0 + HOUR - 1, true),
                new Seen("ETHUSDT.PERP", T0 + HOUR - 1, T0 + HOUR - 1, true),
                new Seen("BTCUSDT.PERP", T0 + 2 * HOUR - 1, T0 + 2 * HOUR - 1, true),
                new Seen("ETHUSDT.PERP", T0 + 2 * HOUR - 1, T0 + 2 * HOUR - 1, true),
                new Seen("BTCUSDT.PERP", T0 + 3 * HOUR - 1, T0 + 3 * HOUR - 1, true),
                new Seen("ETHUSDT.PERP", T0 + 3 * HOUR - 1, T0 + 3 * HOUR - 1, true));
        assertThat(summary.barsReplayed()).isEqualTo(6);
        assertThat(summary.series()).isEqualTo(2);
        assertThat(summary.hasGaps()).isFalse();
        assertThat(summary.firstBusinessTs()).isEqualTo(T0 + HOUR - 1);
        assertThat(summary.lastBusinessTs()).isEqualTo(T0 + 3 * HOUR - 1);
    }

    @Test
    void equalTimestampsFollowTheConfiguredSeriesOrderNotTheAlphabet() {
        repository.put(BTC, Interval.H1, bars(T0, 2, "30000."));
        repository.put(ETH, Interval.H1, bars(T0, 2, "2000."));

        feeder(Duration.ofSeconds(5))
                .replay(repository, List.of(series(ETH), series(BTC)), T0, T0 + HOUR);
        List<String> ethFirst = seen.stream().map(Seen::symbol).toList();

        freshRun();
        feeder(Duration.ofSeconds(5))
                .replay(repository, List.of(series(BTC), series(ETH)), T0, T0 + HOUR);
        List<String> btcFirst = seen.stream().map(Seen::symbol).toList();

        // ETH sorts before BTC alphabetically, so which of the two leads each tie can only
        // come from configuration order. The ties are within one close time: the two bars of
        // a series still interleave with the other series by business time.
        assertThat(ethFirst).containsExactly("ETHUSDT.PERP", "BTCUSDT.PERP",
                "ETHUSDT.PERP", "BTCUSDT.PERP");
        assertThat(btcFirst).containsExactly("BTCUSDT.PERP", "ETHUSDT.PERP",
                "BTCUSDT.PERP", "ETHUSDT.PERP");
    }

    @Test
    void recordsAHoleInTheMiddleAndKeepsReplayingInsteadOfSkippingSilently() {
        List<Kline> withHole = new ArrayList<>(bars(T0, 2, "30000."));
        withHole.add(bar(T0 + 3 * HOUR, "30003"));
        repository.put(BTC, Interval.H1, withHole);

        BacktestDataFeeder.ReplaySummary summary = feeder(Duration.ofSeconds(5))
                .replay(repository, List.of(series(BTC)), T0, T0 + 3 * HOUR);

        assertThat(summary.gaps()).containsExactly(new BacktestDataFeeder.Gap(
                BTC, Interval.H1, T0 + 2 * HOUR, 1));
        assertThat(summary.missingBars()).isEqualTo(1);
        assertThat(summary.hasGaps()).isTrue();
        // the hole does not stop the replay: the bars after it are still delivered
        assertThat(summary.barsReplayed()).isEqualTo(3);
        assertThat(seen).extracting(Seen::businessTs)
                .containsExactly(T0 + HOUR - 1, T0 + 2 * HOUR - 1, T0 + 4 * HOUR - 1);
    }

    @Test
    void recordsATrailingGapWhenTheDataEndsBeforeTheRequestedRange() {
        repository.put(BTC, Interval.H1, bars(T0, 2, "30000."));

        BacktestDataFeeder.ReplaySummary summary = feeder(Duration.ofSeconds(5))
                .replay(repository, List.of(series(BTC)), T0, T0 + 4 * HOUR);

        // asking for five bars and getting two is a silent short backtest unless it is counted
        assertThat(summary.gaps()).containsExactly(new BacktestDataFeeder.Gap(
                BTC, Interval.H1, T0 + 2 * HOUR, 3));
        assertThat(summary.missingBars()).isEqualTo(3);
        assertThat(summary.barsReplayed()).isEqualTo(2);
        assertThat(summary.lastBusinessTs()).isEqualTo(T0 + 2 * HOUR - 1);
    }

    @Test
    void recordsALeadingGapWhenTheStoreStartsAfterTheRequestedRange() {
        repository.put(BTC, Interval.H1, bars(T0 + 2 * HOUR, 2, "30000."));

        BacktestDataFeeder.ReplaySummary summary = feeder(Duration.ofSeconds(5))
                .replay(repository, List.of(series(BTC)), T0, T0 + 3 * HOUR);

        // starting late is the same silent skip as a hole in the middle
        assertThat(summary.gaps()).containsExactly(new BacktestDataFeeder.Gap(
                BTC, Interval.H1, T0, 2));
        assertThat(summary.missingBars()).isEqualTo(2);
        assertThat(summary.barsReplayed()).isEqualTo(2);
    }

    @Test
    void countsEveryMissingBarWhenASeriesHasSeveralHoles() {
        List<Kline> holed = List.of(bar(T0, "1"), bar(T0 + 4 * HOUR, "2"), bar(T0 + 6 * HOUR, "3"));
        repository.put(BTC, Interval.H1, holed);

        BacktestDataFeeder.ReplaySummary summary = feeder(Duration.ofSeconds(5))
                .replay(repository, List.of(series(BTC)), T0, T0 + 6 * HOUR);

        assertThat(summary.gaps()).containsExactly(
                new BacktestDataFeeder.Gap(BTC, Interval.H1, T0 + HOUR, 3),
                new BacktestDataFeeder.Gap(BTC, Interval.H1, T0 + 5 * HOUR, 1));
        assertThat(summary.missingBars()).isEqualTo(4);
    }

    @Test
    void refusesASeriesThatIsOffTheIntervalGrid() {
        repository.put(BTC, Interval.H1, List.of(bar(T0, "1"), bar(T0 + HOUR / 2, "2")));

        BacktestDataFeeder feeder = feeder(Duration.ofSeconds(5));

        assertThatThrownBy(() -> feeder.replay(repository, List.of(series(BTC)), T0, T0 + HOUR))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("off the " + HOUR + "ms grid")
                .hasMessageContaining("BTCUSDT.PERP");
    }

    @Test
    void refusesASeriesWhoseOwnSpacingIsEvenButDoesNotLineUpWithTheRangeStart() {
        // Every delta here is a clean hour, so only anchoring the grid at fromOpenTime catches
        // it. Accepted, the leading hole would be floor-divided and undercounted.
        repository.put(BTC, Interval.H1, bars(T0 + HOUR, 2, "30000."));

        BacktestDataFeeder feeder = feeder(Duration.ofSeconds(5));

        assertThatThrownBy(() -> feeder.replay(repository, List.of(series(BTC)), T0 + HOUR / 2, T0 + 2 * HOUR))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("off the " + HOUR + "ms grid")
                .hasMessageContaining("not a whole number of bars after " + (T0 + HOUR / 2));
    }

    @Test
    void refusesAStoreThatHandsBackUnsortedOrDuplicateBars() {
        List<Kline> unsorted = new ArrayList<>(bars(T0, 2, "30000."));
        unsorted.add(unsorted.get(0));
        repository.put(BTC, Interval.H1, unsorted);

        BacktestDataFeeder feeder = feeder(Duration.ofSeconds(5));

        // a duplicate would otherwise be replayed twice and reported as a gap that is not there
        assertThatThrownBy(() -> feeder.replay(repository, List.of(series(BTC)), T0, T0 + HOUR))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not strictly ascending")
                .hasMessageContaining("BTCUSDT.PERP");
    }

    @Test
    void refusesToReplayAnEmptyRangeInsteadOfReportingAFlatEquityCurve() {
        BacktestDataFeeder feeder = feeder(Duration.ofSeconds(5));

        assertThatThrownBy(() -> feeder.replay(repository, List.of(series(BTC)), T0, T0 + HOUR))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No klines stored for BTCUSDT.PERP")
                .hasMessageContaining("refusing to replay an empty range");
    }

    @Test
    void refusesToReplayBeforeTheEngineIsStarted() {
        repository.put(BTC, Interval.H1, bars(T0, 1, "30000."));
        EventEngine engine = newEngine(clock);
        BacktestDataFeeder feeder = new BacktestDataFeeder(engine, clock, Duration.ofSeconds(1));

        assertThatThrownBy(() -> feeder.replay(repository, List.of(series(BTC)), T0, T0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not running");
    }

    @Test
    void aRoundThatNeverClosesAbortsTheReplayInsteadOfHanging() throws InterruptedException {
        repository.put(BTC, Interval.H1, bars(T0, 5, "30000."));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger handlerEntries = new AtomicInteger();
        EventEngine engine = newEngine(clock);
        engine.registerHandler((event, publisher) -> {
            handlerEntries.incrementAndGet();
            entered.countDown();
            await(release);
        });
        engine.start();
        List<Long> closedRounds = new CopyOnWriteArrayList<>();
        BacktestDataFeeder feeder = new BacktestDataFeeder(engine, clock, Duration.ofMillis(150),
                closedRounds::add);

        assertThatThrownBy(() -> feeder.replay(repository, List.of(series(BTC)), T0, T0 + 4 * HOUR))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Replay stalled")
                .hasMessageContaining("BTCUSDT.PERP");

        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        // exactly one: the feeder gave up on the first bar instead of queueing the other four
        // behind a round that was never going to close
        assertThat(handlerEntries).hasValue(1);
        // a bar whose cascade never finished must not be reported as closed, or an equity
        // sampler would record a half-applied round as that bar's result
        assertThat(closedRounds).isEmpty();
        release.countDown();
    }

    @Test
    void theRoundListenerSeesEachBarOnlyAfterItsWholeCascadeHasClosed() {
        repository.put(BTC, Interval.H1, bars(T0, 3, "30000."));
        List<String> order = new CopyOnWriteArrayList<>();
        List<Long> closedRounds = new CopyOnWriteArrayList<>();
        VirtualClock replayClock = clock;
        EventEngine engine = newEngine(replayClock);
        // one cascade step: each bar produces a signal, so "round closed" is observably later
        // than "the bar was handled"
        engine.registerHandler((event, publisher) -> {
            if (event instanceof KlineEvent kline) {
                order.add("kline:" + kline.timestamp());
                publisher.publish(SignalEvent.of("test", BTC, Direction.LONG, 1.0, "cascade",
                        kline.timestamp()));
            } else if (event instanceof SignalEvent signal) {
                order.add("signal:" + signal.timestamp());
            }
        });
        engine.start();
        BacktestDataFeeder feeder = new BacktestDataFeeder(engine, replayClock, Duration.ofSeconds(5),
                businessTs -> {
                    closedRounds.add(businessTs);
                    order.add("round-closed:" + businessTs);
                });

        feeder.replay(repository, List.of(series(BTC)), T0, T0 + 2 * HOUR);

        assertThat(closedRounds).containsExactly(T0 + HOUR - 1, T0 + 2 * HOUR - 1, T0 + 3 * HOUR - 1);
        // this ordering is the reason the seam exists: a handler cannot express "after the
        // cascade", so anything sampled here is exact by construction rather than by being
        // registered last
        assertThat(order).containsExactly(
                "kline:" + (T0 + HOUR - 1), "signal:" + (T0 + HOUR - 1), "round-closed:" + (T0 + HOUR - 1),
                "kline:" + (T0 + 2 * HOUR - 1), "signal:" + (T0 + 2 * HOUR - 1),
                "round-closed:" + (T0 + 2 * HOUR - 1),
                "kline:" + (T0 + 3 * HOUR - 1), "signal:" + (T0 + 3 * HOUR - 1),
                "round-closed:" + (T0 + 3 * HOUR - 1));
    }

    @Test
    void theSameDataReplaysToTheSameObservedSequence() {
        repository.put(BTC, Interval.H1, bars(T0, 4, "30000."));
        repository.put(ETH, Interval.H1, bars(T0, 4, "2000."));
        List<BacktestDataFeeder.Series> order = List.of(series(BTC), series(ETH));

        feeder(Duration.ofSeconds(5)).replay(repository, order, T0, T0 + 3 * HOUR);
        List<Seen> firstRun = List.copyOf(seen);

        freshRun();
        feeder(Duration.ofSeconds(5)).replay(repository, order, T0, T0 + 3 * HOUR);

        // event ids are a process-wide counter, so they are deliberately not part of Seen
        assertThat(seen).isEqualTo(firstRun);
        assertThat(firstRun).hasSize(8);
    }

    @Test
    void rejectsAnInvertedRangeAnEmptySeriesListAndANonPositiveTimeout() {
        BacktestDataFeeder feeder = feeder(Duration.ofSeconds(5));

        assertThatThrownBy(() -> feeder.replay(repository, List.of(series(BTC)), T0 + HOUR, T0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Empty range");
        assertThatThrownBy(() -> feeder.replay(repository, List.of(), T0, T0 + HOUR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no series given");
        assertThatThrownBy(() -> new BacktestDataFeeder(newEngine(clock), clock, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be positive");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Read-only store; the feeder must never write. */
    private static final class MapKlineRepository implements KlineRepository {

        private final Map<String, List<Kline>> stored = new LinkedHashMap<>();

        void put(Symbol symbol, Interval interval, List<Kline> klines) {
            stored.put(key(symbol, interval), new ArrayList<>(klines));
        }

        @Override
        public List<Kline> load(Symbol symbol, Interval interval, long fromOpenTime, long toOpenTime) {
            return stored.getOrDefault(key(symbol, interval), List.of()).stream()
                    .filter(kline -> kline.openTime() >= fromOpenTime && kline.openTime() <= toOpenTime)
                    .toList();
        }

        @Override
        public int save(Symbol symbol, Interval interval, List<Kline> klines) {
            throw new UnsupportedOperationException("the feeder only reads");
        }

        private static String key(Symbol symbol, Interval interval) {
            return symbol.unified() + "/" + interval.binanceCode();
        }
    }
}
