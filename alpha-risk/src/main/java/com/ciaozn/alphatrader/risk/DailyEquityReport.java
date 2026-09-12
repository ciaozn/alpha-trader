package com.ciaozn.alphatrader.risk;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * One day of the equity record, summarised (T504, P5-4 / SC-06).
 *
 * <p><b>Built from the snapshots, not from a live book.</b> The numbers come out of
 * {@code equity_snapshot}, which is written by the sampler every minute precisely so that a day can be
 * described after the fact. Deriving the report from the running {@code Portfolio} instead would mean
 * the report could only be produced while the process was up - and the interesting days are the ones
 * where it was not.
 *
 * <p><b>What "the day" means.</b> A half-open UTC window, {@code [dayStart, dayEnd)}: an instant
 * belongs to exactly one day, so two consecutive reports cannot both claim the same snapshot or lose
 * it between them.
 *
 * <p><b>Empty days produce nothing.</b> A day with no samples means the process was down, and a report
 * saying "0 snapshots, 0 P&L" would read as a flat day rather than a silent one - so the absence is
 * the message, and the caller decides how to say it.
 */
public record DailyEquityReport(
        long dayStart,
        long dayEnd,
        BigDecimal startEquity,
        BigDecimal endEquity,
        BigDecimal pnl,
        BigDecimal pnlPct,
        BigDecimal maxDrawdownPct,
        int snapshots) {

    private static final int PERCENT_SCALE = 4;

    public static Optional<DailyEquityReport> of(List<EquitySnapshot> snapshots, long dayStart, long dayEnd) {
        if (dayEnd <= dayStart) {
            throw new IllegalArgumentException("dayEnd must be after dayStart");
        }
        List<EquitySnapshot> window = snapshots.stream()
                .filter(snapshot -> snapshot.businessTs() >= dayStart && snapshot.businessTs() < dayEnd)
                .sorted(Comparator.comparingLong(EquitySnapshot::businessTs))
                .toList();
        if (window.isEmpty()) {
            return Optional.empty();
        }
        BigDecimal start = window.get(0).equity();
        BigDecimal end = window.get(window.size() - 1).equity();
        BigDecimal pnl = end.subtract(start);
        BigDecimal pnlPct = start.signum() == 0
                ? BigDecimal.ZERO
                : pnl.multiply(BigDecimal.valueOf(100)).divide(start, PERCENT_SCALE, RoundingMode.HALF_UP);
        return Optional.of(new DailyEquityReport(dayStart, dayEnd, start, end, pnl, pnlPct,
                maxDrawdownPct(window), window.size()));
    }

    /** Largest peak-to-trough fall inside the day, in percent of the running peak. */
    private static BigDecimal maxDrawdownPct(List<EquitySnapshot> window) {
        BigDecimal peak = window.get(0).equity();
        BigDecimal worst = BigDecimal.ZERO;
        for (EquitySnapshot snapshot : window) {
            BigDecimal equity = snapshot.equity();
            if (equity.compareTo(peak) > 0) {
                peak = equity;
                continue;
            }
            if (peak.signum() <= 0) {
                continue;
            }
            BigDecimal drop = peak.subtract(equity)
                    .multiply(BigDecimal.valueOf(100))
                    .divide(peak, PERCENT_SCALE, RoundingMode.HALF_UP);
            if (drop.compareTo(worst) > 0) {
                worst = drop;
            }
        }
        return worst;
    }

    public String subject() {
        return "Alpha Trader daily equity: " + signed(pnl) + " (" + signed(pnlPct) + "%)";
    }

    public String body() {
        return """
                Alpha Trader daily summary
                  window     : %s -> %s (UTC)
                  equity     : %s -> %s
                  day P&L    : %s (%s%%)
                  max drawdown in day: %s%%
                  snapshots  : %d
                """.formatted(java.time.Instant.ofEpochMilli(dayStart), java.time.Instant.ofEpochMilli(dayEnd),
                startEquity.toPlainString(), endEquity.toPlainString(), signed(pnl),
                signed(pnlPct), maxDrawdownPct.toPlainString(), snapshots);
    }

    private static String signed(BigDecimal value) {
        return value.signum() >= 0 ? "+" + value.toPlainString() : value.toPlainString();
    }
}
