package edu.cmpe272.portal.analytics;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Scheduled batch aggregation for historical analytics. Each run deletes and rebuilds the buckets in
 * its window inside one transaction, so reruns are idempotent and a message whose category or urgency
 * arrived late moves out of the "unclassified" bucket. Buckets are UTC hours and UTC days.
 */
@Component
public class AnalyticsAggregator {

    static final Duration HOURLY_WINDOW = Duration.ofHours(48);
    static final int DAILY_WINDOW_DAYS = 3;
    private static final Logger log = LoggerFactory.getLogger(AnalyticsAggregator.class);

    public record JobResult(String job, String trigger, Instant startedAt, Instant finishedAt, int rowsWritten) {
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public AnalyticsAggregator(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    @Scheduled(cron = "${app.analytics.hourly-cron}", zone = "UTC")
    public void scheduledHourly() {
        hourly(Instant.now().minus(HOURLY_WINDOW), "scheduled");
    }

    @Scheduled(cron = "${app.analytics.daily-cron}", zone = "UTC")
    public void scheduledDaily() {
        daily(LocalDate.now(ZoneOffset.UTC).minusDays(DAILY_WINDOW_DAYS), "scheduled");
    }

    /** Full rebuild at startup, so a fresh deployment (or a backfilled seed) has history immediately. */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        hourly(Instant.EPOCH, "startup");
        daily(LocalDate.EPOCH, "startup");
    }

    public JobResult hourly(Instant since, String trigger) {
        Instant from = since.truncatedTo(ChronoUnit.HOURS);
        return run("hourly", trigger, () -> {
            Timestamp f = Timestamp.from(from);
            jdbc.update("DELETE FROM message_stats_hourly WHERE bucket_start >= ?", f);
            int rows = jdbc.update("""
                    INSERT INTO message_stats_hourly
                        (bucket_start, source, category, urgency_label, message_count, reviewed_count, computed_at)
                    SELECT date_trunc('hour', submitted_at AT TIME ZONE 'UTC') AT TIME ZONE 'UTC', source,
                           coalesce(category, 'unclassified'), coalesce(urgency_label, 'unclassified'),
                           count(*), count(*) FILTER (WHERE reviewed_at IS NOT NULL), now()
                    FROM message
                    WHERE submitted_at >= ? AND status <> 'RECEIVED'
                    GROUP BY 1, 2, 3, 4
                    """, f);
            jdbc.update("DELETE FROM service_stats_hourly WHERE bucket_start >= ?", f);
            rows += jdbc.update("""
                    INSERT INTO service_stats_hourly
                        (bucket_start, service, event_count, failure_count, p50_latency_ms, p95_latency_ms, computed_at)
                    SELECT date_trunc('hour', occurred_at AT TIME ZONE 'UTC') AT TIME ZONE 'UTC', service, count(*),
                           count(*) FILTER (WHERE status <> 'SUCCESS'),
                           percentile_disc(0.5) WITHIN GROUP (ORDER BY latency_ms) FILTER (WHERE status = 'SUCCESS'),
                           percentile_disc(0.95) WITHIN GROUP (ORDER BY latency_ms) FILTER (WHERE status = 'SUCCESS'),
                           now()
                    FROM service_event
                    WHERE occurred_at >= ?
                    GROUP BY 1, 2
                    """, f);
            return rows;
        });
    }

    public JobResult daily(LocalDate since, String trigger) {
        return run("daily", trigger, () -> {
            jdbc.update("DELETE FROM message_stats_daily WHERE bucket_date >= ?", since);
            return jdbc.update("""
                    INSERT INTO message_stats_daily
                        (bucket_date, source, category, urgency_label, message_count, reviewed_count, computed_at)
                    SELECT (submitted_at AT TIME ZONE 'UTC')::date, source,
                           coalesce(category, 'unclassified'), coalesce(urgency_label, 'unclassified'),
                           count(*), count(*) FILTER (WHERE reviewed_at IS NOT NULL), now()
                    FROM message
                    WHERE (submitted_at AT TIME ZONE 'UTC')::date >= ? AND status <> 'RECEIVED'
                    GROUP BY 1, 2, 3, 4
                    """, since);
        });
    }

    private JobResult run(String job, String trigger, java.util.function.IntSupplier work) {
        Instant started = Instant.now();
        Integer rows = tx.execute(s -> {
            int written = work.getAsInt();
            jdbc.update("INSERT INTO analytics_job_run (job, trigger, started_at, finished_at, rows_written) "
                    + "VALUES (?, ?, ?, now(), ?)", job, trigger, Timestamp.from(started), written);
            return written;
        });
        JobResult result = new JobResult(job, trigger, started, Instant.now(), rows == null ? 0 : rows);
        log.info("analytics {} aggregation ({}): {} rows in {} ms", job, trigger, result.rowsWritten(),
                Duration.between(started, result.finishedAt()).toMillis());
        return result;
    }
}
