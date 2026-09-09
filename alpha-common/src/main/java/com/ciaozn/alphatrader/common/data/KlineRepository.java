package com.ciaozn.alphatrader.common.data;

import com.ciaozn.alphatrader.common.model.Interval;
import com.ciaozn.alphatrader.common.model.Kline;
import com.ciaozn.alphatrader.common.model.Symbol;

import java.util.List;

/**
 * Historical bar storage: the backtest data source (FR-BT-01) and the download target
 * (FR-BT-05). The contract lives in alpha-common with zero dependencies so a CSV file,
 * a SQLite database and a MySQL database are all interchangeable behind it - swapping the
 * store must not touch the feeder, the strategies or the matcher (FR-BT-06).
 *
 * <p>Implementations own three invariants, because everything downstream relies on them:
 * <ul>
 *   <li>{@link #load} returns closed bars in strictly ascending {@code openTime} order, one
 *       bar per {@code openTime} - the feeder's gap detection counts missing bars, so a
 *       duplicate would be reported as a gap that is not there;</li>
 *   <li>{@link #save} is idempotent: storing the same batch twice changes nothing, which is
 *       what lets the downloader re-run over an overlapping range after a failure;</li>
 *   <li>a bar is stored or returned exactly as given - prices are never re-rounded, so a
 *       replayed bar is bit-identical to the one the exchange sent (NFR-04).</li>
 * </ul>
 */
public interface KlineRepository {

    /**
     * Bars whose {@code openTime} falls in {@code [fromOpenTime, toOpenTime]}, both ends
     * inclusive, ascending. Empty when nothing is stored - callers must treat that as
     * "no data, refuse to run", never as "flat market".
     */
    List<Kline> load(Symbol symbol, Interval interval, long fromOpenTime, long toOpenTime);

    /** Everything stored for one series. */
    default List<Kline> loadAll(Symbol symbol, Interval interval) {
        return load(symbol, interval, 0L, Long.MAX_VALUE);
    }

    /**
     * Stores bars, replacing any bar already held for the same {@code openTime}.
     *
     * @return how many bars were new or changed; 0 means the store already held exactly this
     *         data, so a downloader can use it as its "nothing left to fetch" signal
     */
    int save(Symbol symbol, Interval interval, List<Kline> klines);
}
