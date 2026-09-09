package com.ciaozn.alphatrader.backtest.data;

import com.ciaozn.alphatrader.common.data.KlineRepository;
import com.ciaozn.alphatrader.common.model.Interval;
import com.ciaozn.alphatrader.common.model.Kline;
import com.ciaozn.alphatrader.common.model.Symbol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One CSV file per symbol+interval under a base directory (DESIGN §10's offline playback
 * source): {@code <directory>/BTCUSDT.PERP-1h.csv}.
 *
 * <p>Prices are written with {@code toPlainString} and read back with {@code new BigDecimal},
 * so a bar survives the round trip byte-for-byte - a replayed backtest must not differ from
 * the run that downloaded the data (NFR-04). Rows are kept sorted by {@code openTime} and
 * deduplicated on write, which is what makes {@link #save} idempotent for a downloader
 * re-fetching an overlapping range.
 *
 * <p>Writes go to a sibling {@code .tmp} file and are then moved into place, because a
 * half-written CSV would silently shorten the dataset and the backtest would report plausible
 * numbers computed from missing bars.
 *
 * <p>Single-process and not thread-safe: download first, then run.
 */
public final class CsvKlineRepository implements KlineRepository {

    static final String HEADER = "openTime,open,high,low,close,volume,closeTime";
    private static final int COLUMNS = 7;

    private static final Logger log = LoggerFactory.getLogger(CsvKlineRepository.class);

    private final Path directory;

    public CsvKlineRepository(Path directory) {
        this.directory = directory;
    }

    public Path directory() {
        return directory;
    }

    public Path pathFor(Symbol symbol, Interval interval) {
        return directory.resolve(symbol.unified() + "-" + interval.binanceCode() + ".csv");
    }

    @Override
    public List<Kline> load(Symbol symbol, Interval interval, long fromOpenTime, long toOpenTime) {
        Path path = pathFor(symbol, interval);
        if (!Files.exists(path)) {
            log.debug("No kline file at {}, nothing to replay", path);
            return List.of();
        }
        List<Kline> bars = new ArrayList<>();
        for (Kline kline : read(path).values()) {
            if (kline.openTime() >= fromOpenTime && kline.openTime() <= toOpenTime) {
                bars.add(kline);
            }
        }
        bars.sort(Comparator.comparingLong(Kline::openTime));
        return Collections.unmodifiableList(bars);
    }

    @Override
    public int save(Symbol symbol, Interval interval, List<Kline> klines) {
        Path path = pathFor(symbol, interval);
        Map<Long, Kline> merged = Files.exists(path) ? read(path) : new LinkedHashMap<>();
        int changed = 0;
        for (Kline kline : klines) {
            if (kline.openTime() < 0) {
                throw new IllegalArgumentException("openTime must be >= 0, got " + kline.openTime());
            }
            if (!kline.equals(merged.put(kline.openTime(), kline))) {
                changed++;
            }
        }
        if (changed == 0) {
            return 0;
        }
        write(path, merged);
        log.debug("Wrote {} new/changed bar(s) to {} ({} total)", changed, path, merged.size());
        return changed;
    }

    private Map<Long, Kline> read(Path path) {
        List<String> lines;
        try {
            lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read kline CSV " + path, e);
        }
        Map<Long, Kline> byOpenTime = new LinkedHashMap<>();
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index).trim();
            if (line.isEmpty() || line.regionMatches(true, 0, HEADER, 0, HEADER.length())) {
                continue;
            }
            Kline kline = parse(line, path, index + 1);
            byOpenTime.put(kline.openTime(), kline);
        }
        return byOpenTime;
    }

    private static Kline parse(String line, Path path, int lineNumber) {
        String[] columns = line.split(",", -1);
        if (columns.length != COLUMNS) {
            throw malformed(line, path, lineNumber, "expected " + COLUMNS + " columns, got " + columns.length);
        }
        try {
            return new Kline(Long.parseLong(columns[0].trim()),
                    decimal(columns[1]), decimal(columns[2]), decimal(columns[3]), decimal(columns[4]),
                    decimal(columns[5]), Long.parseLong(columns[6].trim()));
        } catch (NumberFormatException e) {
            throw malformed(line, path, lineNumber, "not a number");
        }
    }

    private static BigDecimal decimal(String column) {
        return new BigDecimal(column.trim());
    }

    private static IllegalArgumentException malformed(String line, Path path, int lineNumber, String why) {
        return new IllegalArgumentException(
                path + ":" + lineNumber + " is not a kline row (" + why + "): '" + line + "'");
    }

    private void write(Path path, Map<Long, Kline> bars) {
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            Files.createDirectories(path.toAbsolutePath().getParent());
            try (BufferedWriter out = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                out.write(HEADER);
                out.write('\n');
                for (Kline kline : bars.values().stream()
                        .sorted(Comparator.comparingLong(Kline::openTime)).toList()) {
                    out.write(format(kline));
                    out.write('\n');
                }
            }
            move(temporary, path);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write kline CSV " + path, e);
        }
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String format(Kline kline) {
        return kline.openTime()
                + "," + kline.open().toPlainString()
                + "," + kline.high().toPlainString()
                + "," + kline.low().toPlainString()
                + "," + kline.close().toPlainString()
                + "," + kline.volume().toPlainString()
                + "," + kline.closeTime();
    }
}
