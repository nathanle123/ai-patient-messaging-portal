package edu.cmpe272.portal.analytics;

import edu.cmpe272.portal.fhir.FhirWriteBackStore;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class AnalyticsDtos {

    private AnalyticsDtos() {
    }

    /** Operational metrics, computed live from the message table. Counts only. */
    public record Summary(String source, Map<String, Long> sourceCounts, Totals totals, Map<String, Long> byCategory,
                          Map<String, Long> byUrgency, Review review, Instant generatedAt) {
    }

    public record Totals(long messages, long processed, long inFlight, long redactionFailed, long reviewed,
                         long unreviewed, long unreviewedUrgent) {
    }

    /** Time to review is measured from published_at (real time), not submitted_at (backdated for seeds). */
    public record Review(long reviewedLast24h, Double medianMinutesToReview, Double p90MinutesToReview) {
    }

    public record TrendBucket(String bucket, long total, Map<String, Long> byCategory, Map<String, Long> byUrgency) {
    }

    public record ServiceTrendPoint(String bucket, String service, long events, long failures, Integer p50LatencyMs,
                                    Integer p95LatencyMs) {
    }

    public record Trends(String granularity, String source, List<TrendBucket> buckets,
                         List<ServiceTrendPoint> serviceLatency, Instant aggregatedAt) {
    }

    public record ServiceStatus(String service, String health, Integer httpStatus, Instant checkedAt,
                                LiveMetrics.ServiceLive live, Long consumerLag) {
    }

    public record Pipeline(long completedLast15m, double completedPerMinute, Double e2eP50Seconds,
                           Double e2eP95Seconds, long inFlight) {
    }

    public record Technical(List<ServiceStatus> services, Pipeline pipeline, boolean kafkaAvailable,
                            Map<String, Long> consumerLag, Map<String, Long> dlqDepth,
                            FhirWriteBackStore.Counts fhirWriteBack, Instant generatedAt) {
    }

}
