package com.ciaozn.alphatrader.gateway;

import com.ciaozn.alphatrader.common.data.KlineRepository;
import com.ciaozn.alphatrader.common.model.Interval;
import com.ciaozn.alphatrader.common.model.Kline;
import com.ciaozn.alphatrader.common.model.Symbol;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory {@link KlineRepository} for gateway tests: keeps the downloader's tests failing for
 * downloader reasons only, without depending on the CSV store (which lives downstream in
 * alpha-backtest) or a database driver.
 */
final class InMemoryKlineRepository implements KlineRepository {

    private final Map<String, Map<Long, Kline>> series = new LinkedHashMap<>();

    private static String key(Symbol symbol, Interval interval) {
        return symbol.unified() + "/" + interval.binanceCode();
    }

    @Override
    public List<Kline> load(Symbol symbol, Interval interval, long fromOpenTime, long toOpenTime) {
        Map<Long, Kline> bars = series.get(key(symbol, interval));
        if (bars == null) {
            return List.of();
        }
        List<Kline> selected = new ArrayList<>();
        for (Kline kline : bars.values()) {
            if (kline.openTime() >= fromOpenTime && kline.openTime() <= toOpenTime) {
                selected.add(kline);
            }
        }
        selected.sort(Comparator.comparingLong(Kline::openTime));
        return List.copyOf(selected);
    }

    @Override
    public int save(Symbol symbol, Interval interval, List<Kline> klines) {
        Map<Long, Kline> bars = series.computeIfAbsent(key(symbol, interval), unused -> new LinkedHashMap<>());
        int changed = 0;
        for (Kline kline : klines) {
            if (!kline.equals(bars.put(kline.openTime(), kline))) {
                changed++;
            }
        }
        return changed;
    }

    int size(Symbol symbol, Interval interval) {
        Map<Long, Kline> bars = series.get(key(symbol, interval));
        return bars == null ? 0 : bars.size();
    }
}
