package com.ciaozn.alphatrader.backtest.feed;

import com.ciaozn.alphatrader.common.data.KlineRepository;
import com.ciaozn.alphatrader.common.event.KlineEvent;
import com.ciaozn.alphatrader.common.model.Interval;
import com.ciaozn.alphatrader.common.model.Kline;
import com.ciaozn.alphatrader.common.model.Symbol;
import com.ciaozn.alphatrader.common.time.VirtualClock;
import com.ciaozn.alphatrader.engine.EventEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Replays stored k-lines through the ordinary event chain, one closed bar at a time
 * (FR-BT-01). Strategies, risk and the matcher cannot tell this from a gateway: they
 * receive the same {@link KlineEvent} with {@code closed=true} and the same business
 * timestamp - the bar's close time - that a live WebSocket feed would carry.
 *
 * <p>Per bar the feeder moves the {@link VirtualClock} to the close, publishes, then
 * waits for the round to close ({@link EventEngine#awaitQuiescence(long)}). Waiting is
 * what makes "one bar == one round" exact: without it the next bar's events would
 * interleave with this bar's signal -&gt; risk -&gt; order -&gt; fill cascade and the run would
 * stop being reproducible (NFR-04).
 *
 * <p>Series are merged by business time, never replayed one after another, so a
 * multi-symbol backtest sees the same interleaving it would see live. Ties (same close
 * time, the normal case for aligned series) fall back to the order the series were
 * given in - configuration order, not hash order.
 */
public final class BacktestDataFeeder {

    private static final Logger log = LoggerFactory.getLogger(BacktestDataFeeder.class);

    private final EventEngine engine;
    private final VirtualClock clock;
    private final Duration quiescenceTimeout;

    /**
     * @param quiescenceTimeout how long to wait for one bar's round to close. A replay
     *                          round is microseconds, so this is a stall detector: it is
     *                          meant to be exceeded only by a deadlocked handler.
     */
    public BacktestDataFeeder(EventEngine engine, VirtualClock clock, Duration quiescenceTimeout) {
        if (quiescenceTimeout.isZero() || quiescenceTimeout.isNegative()) {
            throw new IllegalArgumentException("quiescenceTimeout must be positive: " + quiescenceTimeout);
        }
        this.engine = engine;
        this.clock = clock;
        this.quiescenceTimeout = quiescenceTimeout;
    }

    /** One symbol/interval pair to replay. */
    public record Series(Symbol symbol, Interval interval) {
    }

    /**
     * A hole in the stored data (spec edge case 3): {@code missingBars} bars starting at
     * {@code expectedOpenTime} were asked for and are not there. Covers a store that starts
     * after the requested range, holes in the middle, and a store that ends before it - all
     * three are the same failure and all three reach the report instead of being skipped.
     * The end of the hole is {@code expectedOpenTime + missingBars * interval}, so it is not
     * stored separately.
     */
    public record Gap(Symbol symbol, Interval interval, long expectedOpenTime, long missingBars) {
    }

    /** What a finished replay did, for the report and for the SC-01 timing check. */
    public record ReplaySummary(int series, int barsReplayed, long missingBars, List<Gap> gaps,
                                long firstBusinessTs, long lastBusinessTs, Duration elapsed) {

        public boolean hasGaps() {
            return !gaps.isEmpty();
        }
    }

    /**
     * Replays every stored bar whose open time falls in {@code [fromOpenTime, toOpenTime]}.
     *
     * @throws IllegalStateException if a series has no data at all, the engine is not
     *                               running, a series is not on the interval grid, the store
     *                               hands back unsorted or duplicate bars, or a round fails
     *                               to close. An empty range is refused rather than replayed:
     *                               a zero-bar backtest reports a flat equity curve, which
     *                               reads as "strategy made no money" instead of "there was no
     *                               data".
     */
    public ReplaySummary replay(KlineRepository repository, List<Series> seriesList,
                                long fromOpenTime, long toOpenTime) {
        if (seriesList.isEmpty()) {
            throw new IllegalArgumentException("Nothing to replay: no series given");
        }
        if (toOpenTime < fromOpenTime) {
            throw new IllegalArgumentException(
                    "Empty range: toOpenTime " + toOpenTime + " < fromOpenTime " + fromOpenTime);
        }
        if (!engine.isRunning()) {
            throw new IllegalStateException("EventEngine is not running - start it before replaying");
        }

        List<Gap> gaps = new ArrayList<>();
        List<Bar> merged = new ArrayList<>();
        long missingBars = 0;
        for (Series series : seriesList) {
            List<Kline> klines = repository.load(series.symbol(), series.interval(), fromOpenTime, toOpenTime);
            if (klines.isEmpty()) {
                throw new IllegalStateException("No klines stored for " + series.symbol() + " "
                        + series.interval() + " in [" + fromOpenTime + ", " + toOpenTime
                        + "] - refusing to replay an empty range");
            }
            missingBars += scanGaps(series, klines, fromOpenTime, toOpenTime, gaps);
            for (Kline kline : klines) {
                merged.add(new Bar(series, kline));
            }
        }
        // Stable sort: equal close times keep the order the series were configured in.
        merged.sort(Comparator.comparingLong(bar -> bar.kline().closeTime()));

        long startedNanos = System.nanoTime();
        long firstBusinessTs = 0;
        long lastBusinessTs = 0;
        int replayed = 0;
        for (Bar bar : merged) {
            long businessTs = bar.kline().closeTime();
            KlineEvent event = KlineEvent.of(bar.series().symbol(), bar.series().interval(),
                    bar.kline(), true, businessTs);
            advanceAndPublish(businessTs, event);
            awaitRound(event, bar);
            if (replayed == 0) {
                firstBusinessTs = businessTs;
            }
            lastBusinessTs = businessTs;
            replayed++;
        }
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedNanos);

        if (!gaps.isEmpty()) {
            log.warn("Replayed {} bar(s) over {} series with {} data gap(s) totalling {} missing bar(s);"
                            + " first gap: {}", replayed, seriesList.size(), gaps.size(), missingBars, gaps.get(0));
        } else {
            log.info("Replayed {} bar(s) over {} series in {}, no data gaps", replayed, seriesList.size(), elapsed);
        }
        return new ReplaySummary(seriesList.size(), replayed, missingBars, List.copyOf(gaps),
                firstBusinessTs, lastBusinessTs, elapsed);
    }

    /**
     * Moves the clock to this bar's close and only then hands the event to the engine. The
     * order is what makes it correct, and it is correct by the memory model rather than by
     * winning a race: {@code advanceTo} happens-before the queue's offer, and the loop
     * thread's poll synchronizes-with that offer, so every handler dispatched for this event
     * reads this bar's close.
     *
     * <p>Reversed, a handler could observe the previous bar's time. That is not cosmetic: any
     * timer due exactly at this close - funding, in particular - would look not-yet-due to the
     * quiescence barrier, the barrier would release, and the timer would fire attributed to
     * the next bar, so the same data would no longer replay bit-identically (NFR-04). No test
     * can catch the reversal: the two statements are adjacent, so the wrong order still reads
     * correctly almost every time and only flips under a GC or JIT pause.
     */
    private void advanceAndPublish(long businessTs, KlineEvent event) {
        clock.advanceTo(businessTs);
        engine.publish(event);
    }

    private void awaitRound(KlineEvent event, Bar bar) {
        boolean quiescent;
        try {
            quiescent = engine.awaitQuiescence(event.eventId(), quiescenceTimeout);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Replay interrupted at " + describe(bar), e);
        }
        if (!quiescent) {
            throw new IllegalStateException("Replay stalled: the round for " + describe(bar)
                    + " did not close within " + quiescenceTimeout + " (a handler is blocking the event loop?)");
        }
    }

    private static String describe(Bar bar) {
        return bar.series().symbol() + " " + bar.series().interval() + " openTime=" + bar.kline().openTime();
    }

    /**
     * Walks one series, appends every hole to {@code gaps} and returns the total number of
     * missing bars. {@code expected} is the open time of the next bar the range calls for, so
     * one comparison covers a late start, an interior hole and an early end alike.
     *
     * <p>Bars are checked against the grid anchored at {@code fromOpenTime}, not just against
     * their predecessor: delta-only checking accepted a series that started off-grid and then
     * floor-divided the leading hole, quietly reporting fewer missing bars than there were.
     */
    private static long scanGaps(Series series, List<Kline> klines, long fromOpenTime, long toOpenTime,
                                 List<Gap> gaps) {
        long step = series.interval().duration().toMillis();
        long missing = 0;
        long expected = fromOpenTime;
        long previous = Long.MIN_VALUE;
        for (Kline kline : klines) {
            long actual = kline.openTime();
            if (actual <= previous) {
                throw new IllegalStateException("Klines for " + series.symbol() + " " + series.interval()
                        + " are not strictly ascending: openTime " + actual + " follows " + previous);
            }
            if ((actual - fromOpenTime) % step != 0) {
                throw new IllegalStateException("Klines for " + series.symbol() + " " + series.interval()
                        + " are off the " + step + "ms grid: openTime " + actual + " is not a whole number of"
                        + " bars after " + fromOpenTime);
            }
            if (actual > expected) {
                missing += addGap(series, expected, actual, step, gaps);
            }
            expected = actual + step;
            previous = actual;
        }
        // Data ending before the requested range is the same silent truncation as a hole, so
        // it is counted too: toOpenTime itself still calls for a bar, hence the extra step.
        if (expected <= toOpenTime) {
            missing += addGap(series, expected, toOpenTime + step, step, gaps);
        }
        return missing;
    }

    /** One hole spanning {@code [from, end)}, counted in whole bars. */
    private static long addGap(Series series, long from, long end, long step, List<Gap> gaps) {
        long bars = (end - from) / step;
        gaps.add(new Gap(series.symbol(), series.interval(), from, bars));
        return bars;
    }

    private record Bar(Series series, Kline kline) {
    }
}
