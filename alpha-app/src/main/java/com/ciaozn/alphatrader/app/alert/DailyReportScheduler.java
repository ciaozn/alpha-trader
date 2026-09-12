package com.ciaozn.alphatrader.app.alert;

import com.ciaozn.alphatrader.common.time.Clock;
import com.ciaozn.alphatrader.risk.DailyEquityReport;
import com.ciaozn.alphatrader.risk.RecordStore;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Sends the previous day's equity summary once a day (T504, P5-4).
 *
 * <p><b>Its own thread, and a timer rather than the engine's.</b> The work is a database read plus a
 * blocking SMTP send; neither belongs on the event loop. The engine's timers exist for things that
 * must happen in the sequence of market events - this is a calendar job with nothing to sequence
 * against.
 *
 * <p><b>05:00 UTC, not midnight.</b> At exactly 00:00 the day boundary and the last snapshot of the
 * previous day can collide, and the summary would occasionally be built from a window that a
 * straggling sample had not yet joined. Five minutes of margin costs nothing.
 *
 * <p><b>A day with no snapshots is reported as silence, not as a flat day.</b> The process being down
 * is the one thing a daily report must not hide, so that case is logged as a warning and mailed with
 * an explicit "no samples" subject rather than a zero P&L.
 */
@Component
@Profile({"paper", "live"})
public class DailyReportScheduler {

    private static final Logger log = LoggerFactory.getLogger(DailyReportScheduler.class);
    private static final int FIRST_RUN_HOUR_UTC = 5;

    private final RecordStore records;
    private final AlertTransport transport;
    private final AlertProperties alertProperties;
    private final Clock clock;
    private final ScheduledExecutorService scheduler;

    public DailyReportScheduler(RecordStore records, AlertTransport transport,
                                AlertProperties alertProperties, Clock clock) {
        this.records = records;
        this.transport = transport;
        this.alertProperties = alertProperties;
        this.clock = clock;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "daily-report");
            thread.setDaemon(true);
            return thread;
        });
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        long initialDelay = millisUntilNextRun();
        scheduler.scheduleAtFixedRate(this::runSafely, initialDelay,
                Duration.ofDays(1).toMillis(), TimeUnit.MILLISECONDS);
        log.info("Daily equity report scheduled: first run in {} minutes",
                initialDelay / 60_000);
    }

    @PreDestroy
    public void stop() {
        scheduler.shutdownNow();
    }

    /** Sends (or logs) the summary for the day that just ended. Exposed for tests and manual runs. */
    public void runOnce() {
        long dayEnd = startOfTodayUtc();
        long dayStart = dayEnd - Duration.ofDays(1).toMillis();
        var report = DailyEquityReport.of(records.equitySnapshots(dayStart, dayEnd), dayStart, dayEnd);
        if (report.isEmpty()) {
            log.warn("Daily report: no equity snapshots between {} and {} - the process was down or"
                    + " the sampler never ran", Instant.ofEpochMilli(dayStart), Instant.ofEpochMilli(dayEnd));
            if (alertProperties.active()) {
                transport.send("Alpha Trader daily equity: NO SAMPLES",
                        "No equity snapshots were recorded for the day ending "
                                + Instant.ofEpochMilli(dayEnd) + " (UTC).\n"
                                + "That means the process was not running, or its store was not reachable.\n"
                                + "Nothing about the market is implied by this message.\n");
            }
            return;
        }
        DailyEquityReport summary = report.get();
        log.info("Daily report for {}: {}", Instant.ofEpochMilli(dayStart), summary.subject());
        if (alertProperties.active()) {
            transport.send(summary.subject(), summary.body());
        } else {
            log.info("\n{}", summary.body());
        }
    }

    private void runSafely() {
        try {
            runOnce();
        } catch (RuntimeException e) {
            log.error("Daily report failed", e);
        }
    }

    private long startOfTodayUtc() {
        ZonedDateTime now = Instant.ofEpochMilli(clock.nowMillis()).atZone(ZoneOffset.UTC);
        return now.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
    }

    private long millisUntilNextRun() {
        ZonedDateTime now = Instant.ofEpochMilli(clock.nowMillis()).atZone(ZoneOffset.UTC);
        ZonedDateTime next = now.toLocalDate().atStartOfDay(ZoneOffset.UTC)
                .plusHours(FIRST_RUN_HOUR_UTC);
        if (!next.isAfter(now)) {
            next = next.plusDays(1);
        }
        return Duration.between(now, next).toMillis();
    }
}
