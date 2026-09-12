package com.ciaozn.alphatrader.backtest.report;

import com.ciaozn.alphatrader.common.event.SignalEvent;
import com.ciaozn.alphatrader.common.model.Direction;
import com.ciaozn.alphatrader.common.model.Symbol;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Compares two signal sequences over the same window (T505, SC-05).
 *
 * <p><b>What SC-05 is really asking.</b> "实盘信号序列与同期回测信号序列一致" is the only check in the
 * project that can catch a drift between the two modes that both have tests: the backtest and the live
 * path share the strategy code, the indicators and the risk pipeline, but they do not share the feeder,
 * the clock or the matcher. A mistake in any of those three shows up here and nowhere else - and it
 * shows up as a signal that fired in one mode and not the other, which is exactly what this class
 * counts.
 *
 * <p><b>Identity is (strategy, symbol, direction, bar time)</b>, not the whole event: the event id is
 * allocated per process, and the strength and reason are free text a strategy may reword. What must
 * match for the two runs to be the same system is which strategy decided what, on which bar.
 *
 * <p><b>Counted as multisets, not sets.</b> Two signals from one strategy on one bar is a different
 * run from one, and a set comparison would report them as identical - the kind of quiet
 * over-permissiveness that makes a consistency check useless.
 *
 * <p>Nothing here throws or fails a run: the caller decides what an inconsistent result means. During
 * the P5 observation it is a report; it would only become a gate after the two modes are known to
 * agree.
 */
public record SignalConsistencyReport(
        int referenceCount,
        int actualCount,
        List<SignalEvent> missing,
        List<SignalEvent> extra) {

    /** What makes two signals "the same decision": strategy, symbol, direction and the bar they were made on. */
    private record Key(String strategyId, Symbol symbol, Direction direction, long barTime) {

        static Key of(SignalEvent signal) {
            return new Key(signal.strategyId(), signal.symbol(), signal.direction(), signal.timestamp());
        }
    }

    public SignalConsistencyReport {
        missing = List.copyOf(missing);
        extra = List.copyOf(extra);
    }

    /** @param reference the sequence to check against (a backtest replay of the same window) */
    public static SignalConsistencyReport compare(List<SignalEvent> reference, List<SignalEvent> actual) {
        Map<Key, Integer> counts = new HashMap<>();
        for (SignalEvent signal : reference) {
            counts.merge(Key.of(signal), 1, Integer::sum);
        }
        List<SignalEvent> extra = new ArrayList<>();
        for (SignalEvent signal : actual) {
            Integer remaining = counts.get(Key.of(signal));
            if (remaining == null || remaining == 0) {
                extra.add(signal);
            } else {
                counts.put(Key.of(signal), remaining - 1);
            }
        }
        List<SignalEvent> missing = new ArrayList<>();
        counts.forEach((key, remaining) -> {
            if (remaining > 0) {
                for (int i = 0; i < remaining; i++) {
                    missing.add(matching(reference, key));
                }
            }
        });
        return new SignalConsistencyReport(reference.size(), actual.size(), missing, extra);
    }

    /** The instance from the reference side, so a report shows the signal itself and not just a key. */
    private static SignalEvent matching(List<SignalEvent> reference, Key key) {
        return reference.stream().filter(signal -> Key.of(signal).equals(key)).findFirst().orElseThrow();
    }

    public int matched() {
        return referenceCount - missing.size();
    }

    public boolean consistent() {
        return missing.isEmpty() && extra.isEmpty();
    }

    public String render() {
        StringBuilder out = new StringBuilder();
        out.append(consistent()
                ? "Signals agree: " + referenceCount + " signal(s) matched on both sides."
                : "Signals DISAGREE: " + referenceCount + " reference vs " + actualCount + " actual, "
                        + missing.size() + " missing, " + extra.size() + " extra.");
        missing.forEach(signal -> out.append("\n  missing: ").append(describe(signal)));
        extra.forEach(signal -> out.append("\n  extra:   ").append(describe(signal)));
        return out.toString();
    }

    private static String describe(SignalEvent signal) {
        return signal.strategyId() + " " + signal.symbol().unified() + " " + signal.direction()
                + " @ " + java.time.Instant.ofEpochMilli(signal.timestamp()) + " (" + signal.reason() + ")";
    }
}
