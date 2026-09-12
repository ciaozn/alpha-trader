package com.ciaozn.alphatrader.risk;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DailyEquityReportTest {

    private static final long DAY_START = 1_700_000_000_000L;
    private static final long DAY_END = DAY_START + 86_400_000L;

    private static EquitySnapshot at(long offsetMillis, String equity) {
        return new EquitySnapshot(DAY_START + offsetMillis, new BigDecimal(equity));
    }

    @Test
    void summarisesTheDay() {
        var report = DailyEquityReport.of(List.of(
                at(0, "1000"),
                at(3_600_000, "1050"),
                at(7_200_000, "990"),
                at(10_000_000, "1020")), DAY_START, DAY_END).orElseThrow();

        assertThat(report.startEquity()).isEqualByComparingTo(new BigDecimal("1000"));
        assertThat(report.endEquity()).isEqualByComparingTo(new BigDecimal("1020"));
        assertThat(report.pnl()).isEqualByComparingTo(new BigDecimal("20"));
        assertThat(report.pnlPct()).isEqualByComparingTo(new BigDecimal("2.0000"));
        // Peak 1050 -> trough 990 is a 5.7143% fall, and it is measured inside the day only.
        assertThat(report.maxDrawdownPct()).isEqualByComparingTo(new BigDecimal("5.7143"));
        assertThat(report.snapshots()).isEqualTo(4);
    }

    @Test
    void aDayWithNoSamplesProducesNothing() {
        // An absent report says "the process was not running"; a zero report would say "nothing
        // happened", which is a different claim.
        assertThat(DailyEquityReport.of(List.of(at(-1, "1000"), at(86_400_000, "1000")),
                DAY_START, DAY_END)).isEmpty();
    }

    @Test
    void theWindowIsHalfOpenSoSnapshotsBelongToOneDay() {
        // A snapshot exactly at the boundary belongs to the day that starts there, not to both.
        assertThat(DailyEquityReport.of(List.of(at(86_400_000, "1000")), DAY_START, DAY_END)).isEmpty();
        assertThat(DailyEquityReport.of(List.of(at(86_400_000, "1000")), DAY_END, DAY_END + 86_400_000L))
                .isPresent();
    }

    @Test
    void aSingleSnapshotDayIsFlatNotMissing() {
        var report = DailyEquityReport.of(List.of(at(0, "1000")), DAY_START, DAY_END).orElseThrow();

        assertThat(report.pnl()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(report.pnlPct()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(report.maxDrawdownPct()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void aRisingDayHasNoDrawdown() {
        var report = DailyEquityReport.of(List.of(at(0, "1000"), at(3_600_000, "1010"),
                at(7_200_000, "1030")), DAY_START, DAY_END).orElseThrow();

        assertThat(report.maxDrawdownPct()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void theEmailSaysWhatItIsAndCarriesSigns() {
        var report = DailyEquityReport.of(List.of(at(0, "1000"), at(3_600_000, "975")),
                DAY_START, DAY_END).orElseThrow();

        assertThat(report.subject()).contains("-25").contains("-2.5000%");
        assertThat(report.body()).contains("equity").contains("max drawdown in day").contains("snapshots");
    }
}
