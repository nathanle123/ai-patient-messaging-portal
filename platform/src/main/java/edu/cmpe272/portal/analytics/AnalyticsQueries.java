package edu.cmpe272.portal.analytics;

import edu.cmpe272.portal.analytics.AnalyticsDtos.Pipeline;
import edu.cmpe272.portal.analytics.AnalyticsDtos.Review;
import edu.cmpe272.portal.analytics.AnalyticsDtos.ServiceTrendPoint;
import edu.cmpe272.portal.analytics.AnalyticsDtos.Summary;
import edu.cmpe272.portal.analytics.AnalyticsDtos.Totals;
import edu.cmpe272.portal.analytics.AnalyticsDtos.TrendBucket;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Read side of analytics. Every query returns counts or timings; none touches message text. */
@Repository
public class AnalyticsQueries {

    public static final Set<String> SOURCES = Set.of("all", "manual", "portalpal", "synthetic");
    static final List<String> CATEGORIES =
            List.of("medication_refill", "scheduling", "test_results", "billing", "symptoms", "other", "unclassified");
    static final List<String> URGENCIES = List.of("urgent", "soon", "routine", "unclassified");
    /** Bound twice: (? = 'all' OR source = ?). */
    private static final String SOURCE_FILTER = "(? = 'all' OR source = ?)";

    private final JdbcTemplate jdbc;

    public AnalyticsQueries(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Summary summary(String source) {
        Map<String, Long> sourceCounts = new TreeMap<>();
        jdbc.query("SELECT source, count(*) FROM message WHERE status <> 'RECEIVED' GROUP BY 1",
                rs -> { sourceCounts.put(rs.getString(1), rs.getLong(2)); });
        Totals totals = jdbc.queryForObject("""
                SELECT count(*),
                       count(*) FILTER (WHERE status = 'PROCESSED'),
                       count(*) FILTER (WHERE status IN ('REDACTED', 'PUBLISHED')),
                       count(*) FILTER (WHERE status = 'REDACTION_FAILED'),
                       count(*) FILTER (WHERE reviewed_at IS NOT NULL),
                       count(*) FILTER (WHERE reviewed_at IS NULL),
                       count(*) FILTER (WHERE reviewed_at IS NULL AND urgency_label = 'urgent')
                FROM message WHERE status <> 'RECEIVED' AND """ + SOURCE_FILTER,
                (rs, i) -> new Totals(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5),
                        rs.getLong(6), rs.getLong(7)), source, source);
        Review review = jdbc.queryForObject("""
                SELECT count(*) FILTER (WHERE reviewed_at >= now() - interval '24 hours'),
                       percentile_cont(0.5) WITHIN GROUP (ORDER BY extract(epoch FROM reviewed_at - published_at) / 60),
                       percentile_cont(0.9) WITHIN GROUP (ORDER BY extract(epoch FROM reviewed_at - published_at) / 60)
                FROM message WHERE reviewed_at IS NOT NULL AND published_at IS NOT NULL AND """ + SOURCE_FILTER,
                (rs, i) -> new Review(rs.getLong(1), (Double) rs.getObject(2), (Double) rs.getObject(3)), source, source);
        return new Summary(source, sourceCounts, totals,
                countBy("coalesce(category, 'unclassified')", CATEGORIES, source),
                countBy("coalesce(urgency_label, 'unclassified')", URGENCIES, source), review, Instant.now());
    }

    private Map<String, Long> countBy(String expression, List<String> keys, String source) {
        Map<String, Long> out = zeroes(keys);
        jdbc.query("SELECT " + expression + ", count(*) FROM message WHERE status <> 'RECEIVED' AND " + SOURCE_FILTER
                + " GROUP BY 1", rs -> { out.put(rs.getString(1), rs.getLong(2)); }, source, source);
        return out;
    }

    /** Hourly buckets from message_stats_hourly, with empty hours filled in. */
    public List<TrendBucket> hourly(String source, int hours) {
        Instant end = Instant.now().truncatedTo(ChronoUnit.HOURS);
        Instant start = end.minus(hours - 1L, ChronoUnit.HOURS);
        Map<String, TrendAccumulator> buckets = new LinkedHashMap<>();
        for (Instant t = start; !t.isAfter(end); t = t.plus(1, ChronoUnit.HOURS)) {
            buckets.put(t.toString(), new TrendAccumulator());
        }
        jdbc.query("""
                SELECT bucket_start, category, urgency_label, sum(message_count)
                FROM message_stats_hourly WHERE bucket_start >= ? AND """ + SOURCE_FILTER + """
                 GROUP BY 1, 2, 3""", rs -> {
            var acc = buckets.get(rs.getTimestamp(1).toInstant().toString());
            if (acc != null) {
                acc.add(rs.getString(2), rs.getString(3), rs.getLong(4));
            }
        }, Timestamp.from(start), source, source);
        return toBuckets(buckets);
    }

    /** Daily buckets (UTC days) from message_stats_daily, with empty days filled in. */
    public List<TrendBucket> daily(String source, int days) {
        LocalDate end = LocalDate.now(ZoneOffset.UTC);
        LocalDate start = end.minusDays(days - 1L);
        Map<String, TrendAccumulator> buckets = new LinkedHashMap<>();
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            buckets.put(d.toString(), new TrendAccumulator());
        }
        jdbc.query("""
                SELECT bucket_date, category, urgency_label, sum(message_count)
                FROM message_stats_daily WHERE bucket_date >= ? AND """ + SOURCE_FILTER + """
                 GROUP BY 1, 2, 3""", rs -> {
            var acc = buckets.get(rs.getDate(1).toLocalDate().toString());
            if (acc != null) {
                acc.add(rs.getString(2), rs.getString(3), rs.getLong(4));
            }
        }, start, source, source);
        return toBuckets(buckets);
    }

    public List<ServiceTrendPoint> serviceHourly(int hours) {
        Instant start = Instant.now().truncatedTo(ChronoUnit.HOURS).minus(hours - 1L, ChronoUnit.HOURS);
        return jdbc.query("""
                SELECT bucket_start, service, event_count, failure_count, p50_latency_ms, p95_latency_ms
                FROM service_stats_hourly WHERE bucket_start >= ? ORDER BY bucket_start, service
                """, (rs, i) -> new ServiceTrendPoint(rs.getTimestamp(1).toInstant().toString(), rs.getString(2),
                rs.getLong(3), rs.getLong(4), (Integer) rs.getObject(5), (Integer) rs.getObject(6)), Timestamp.from(start));
    }

    public Instant lastAggregation(String job) {
        List<Timestamp> t = jdbc.queryForList(
                "SELECT max(finished_at) FROM analytics_job_run WHERE job = ?", Timestamp.class, job);
        return t.isEmpty() || t.get(0) == null ? null : t.get(0).toInstant();
    }

    /** Messages whose fourth AI result landed recently, and their end-to-end time from publish. */
    public Pipeline pipeline() {
        return jdbc.queryForObject("""
                WITH done AS (
                    SELECT m.id, m.published_at, max(r.created_at) AS completed_at
                    FROM message m JOIN ai_result r ON r.message_id = m.id
                    WHERE m.status = 'PROCESSED' AND m.published_at IS NOT NULL
                    GROUP BY m.id, m.published_at
                    HAVING max(r.created_at) >= now() - interval '60 minutes'
                )
                SELECT count(*) FILTER (WHERE completed_at >= now() - interval '15 minutes'),
                       percentile_cont(0.5) WITHIN GROUP (ORDER BY extract(epoch FROM completed_at - published_at)),
                       percentile_cont(0.95) WITHIN GROUP (ORDER BY extract(epoch FROM completed_at - published_at)),
                       (SELECT count(*) FROM message WHERE status IN ('REDACTED', 'PUBLISHED'))
                FROM done
                """, (rs, i) -> new Pipeline(rs.getLong(1), rs.getLong(1) / 15.0, (Double) rs.getObject(2),
                (Double) rs.getObject(3), rs.getLong(4)));
    }

    private static List<TrendBucket> toBuckets(Map<String, TrendAccumulator> buckets) {
        List<TrendBucket> out = new ArrayList<>();
        buckets.forEach((k, v) -> out.add(new TrendBucket(k, v.total, v.byCategory, v.byUrgency)));
        return out;
    }

    private static Map<String, Long> zeroes(List<String> keys) {
        Map<String, Long> m = new LinkedHashMap<>();
        keys.forEach(k -> m.put(k, 0L));
        return m;
    }

    static final class TrendAccumulator {
        long total;
        final Map<String, Long> byCategory = zeroes(CATEGORIES);
        final Map<String, Long> byUrgency = zeroes(URGENCIES);

        void add(String category, String urgency, long count) {
            total += count;
            byCategory.merge(category, count, Long::sum);
            byUrgency.merge(urgency, count, Long::sum);
        }
    }
}
