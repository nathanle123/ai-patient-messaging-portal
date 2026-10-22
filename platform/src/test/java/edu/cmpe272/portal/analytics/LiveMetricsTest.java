package edu.cmpe272.portal.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class LiveMetricsTest {

    private final Instant now = Instant.parse("2026-10-21T12:00:00Z");

    @Test
    void computesThroughputErrorRateAndPercentilesFromSuccessesOnly() {
        LiveMetrics live = new LiveMetrics(Duration.ofMinutes(60));
        for (int i = 1; i <= 10; i++) {
            live.record("routing", true, i * 100, now.minusSeconds(30));   // 10 recent successes, 100..1000 ms
        }
        live.record("routing", false, 50_000, now.minusSeconds(10));       // a failure must not skew latency
        live.record("routing", true, 100, now.minus(Duration.ofMinutes(20))); // in window, not in last 5 min

        var r = live.snapshot(now).get("routing");

        assertThat(r.eventsInWindow()).isEqualTo(12);
        assertThat(r.eventsLast5m()).isEqualTo(11);
        assertThat(r.perMinute()).isEqualTo(11 / 5.0);
        assertThat(r.failuresInWindow()).isEqualTo(1);
        assertThat(r.errorRate()).isEqualTo(1 / 12.0);
        assertThat(r.p50LatencyMs()).isEqualTo(500);
        assertThat(r.p95LatencyMs()).isEqualTo(1000);
    }

    @Test
    void samplesOutsideTheWindowAreDropped() {
        LiveMetrics live = new LiveMetrics(Duration.ofMinutes(60));
        live.record("urgency", true, 10, now.minus(Duration.ofMinutes(61)));

        var r = live.snapshot(now).get("urgency");

        assertThat(r.eventsInWindow()).isZero();
        assertThat(r.p50LatencyMs()).isNull();
        assertThat(r.errorRate()).isZero();
    }

    @Test
    void percentileOfEmptyIsNull() {
        assertThat(LiveMetrics.percentile(new int[0], 0.5)).isNull();
        assertThat(LiveMetrics.percentile(new int[] {7}, 0.95)).isEqualTo(7);
    }
}
