package com.ciaozn.alphatrader.strategy;

import com.ciaozn.alphatrader.common.model.Kline;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.ToDoubleFunction;

/**
 * Fixed-capacity ring buffer of closed bars for one symbol/interval (DESIGN §7.1).
 *
 * <p>Acceptance rules, which together implement spec edge case 1 (duplicate / out-of-order
 * pushes): a bar is appended only when its openTime is strictly newer than the last accepted
 * one. Re-deliveries of the same bar and stale bars are counted and dropped, so a strategy
 * re-evaluating an identical window cannot emit a duplicate signal.
 *
 * <p>Not thread-safe: only the event-engine thread touches it.
 */
public final class BarSeries {

    private final Kline[] bars;
    private final int capacity;
    private int size;
    private int writeIndex;
    private long lastOpenTime = Long.MIN_VALUE;
    private int duplicates;
    private int staleBars;

    public BarSeries(int capacity) {
        if (capacity < 2) {
            throw new IllegalArgumentException("BarSeries capacity must be >= 2, got " + capacity);
        }
        this.capacity = capacity;
        this.bars = new Kline[capacity];
    }

    /** @return true when the bar was appended, false when it was a duplicate or stale. */
    public boolean offer(Kline bar) {
        if (size > 0 && bar.openTime() <= lastOpenTime) {
            if (bar.openTime() == lastOpenTime) {
                duplicates++;
            } else {
                staleBars++;
            }
            return false;
        }
        bars[writeIndex] = bar;
        writeIndex = (writeIndex + 1) % capacity;
        if (size < capacity) {
            size++;
        }
        lastOpenTime = bar.openTime();
        return true;
    }

    public int size() {
        return size;
    }

    public int capacity() {
        return capacity;
    }

    public boolean hasBars(int required) {
        return size >= required;
    }

    /** The most recent bar. */
    public Kline last() {
        return ago(0);
    }

    /** {@code back=0} is the newest bar, {@code back=1} the one before it. */
    public Kline ago(int back) {
        if (back < 0 || back >= size) {
            throw new IndexOutOfBoundsException(
                    "Bar index " + back + " out of range, series holds " + size + " bars");
        }
        int index = (writeIndex - 1 - back) % capacity;
        if (index < 0) {
            index += capacity;
        }
        return bars[index];
    }

    /** Last {@code count} bars, oldest first. */
    public List<Kline> window(int count) {
        if (count > size) {
            throw new IndexOutOfBoundsException(
                    "Requested " + count + " bars but series holds " + size);
        }
        List<Kline> window = new ArrayList<>(count);
        for (int back = count - 1; back >= 0; back--) {
            window.add(ago(back));
        }
        return Collections.unmodifiableList(window);
    }

    /** Last {@code count} values of one numeric field as doubles, oldest first (indicator input). */
    public double[] doubles(int count, ToDoubleFunction<Kline> extractor) {
        List<Kline> window = window(count);
        double[] values = new double[count];
        for (int i = 0; i < count; i++) {
            values[i] = extractor.applyAsDouble(window.get(i));
        }
        return values;
    }

    public double[] closes(int count) {
        return doubles(count, bar -> bar.close().doubleValue());
    }

    public double[] highs(int count) {
        return doubles(count, bar -> bar.high().doubleValue());
    }

    public double[] lows(int count) {
        return doubles(count, bar -> bar.low().doubleValue());
    }

    /** Dropped re-deliveries of an already-accepted bar. */
    public int duplicateCount() {
        return duplicates;
    }

    /** Dropped bars older than the newest accepted one. */
    public int staleCount() {
        return staleBars;
    }
}
