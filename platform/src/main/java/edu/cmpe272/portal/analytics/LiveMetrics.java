package edu.cmpe272.portal.analytics;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Live technical metrics per service, updated from {@code service.events} as they arrive. Holds a
 * sliding window of samples (status and latency only, never message content) and computes throughput,
 * error rate, and latency percentiles on demand.
 */
public class LiveMetrics {

    static final Duration THROUGHPUT_WINDOW = Duration.ofMinutes(5);

    record Sample(Instant at, boolean success, int latencyMs) {
    }

    public record ServiceLive(String service, long eventsInWindow, long eventsLast5m, double perMinute,
                              long failuresInWindow, double errorRate, Integer p50LatencyMs, Integer p95LatencyMs,
                              Instant lastEventAt) {
    }

    private final Map<String, Deque<Sample>> samples = new ConcurrentHashMap<>();
    private final Duration window;

    public LiveMetrics(Duration window) {
        this.window = window;
    }

    public void record(String service, boolean success, int latencyMs, Instant at) {
        samples.computeIfAbsent(service, s -> new ConcurrentLinkedDeque<>()).addLast(new Sample(at, success, latencyMs));
    }

    public Map<String, ServiceLive> snapshot(Instant now) {
        Instant cutoff = now.minus(window);
        Instant recent = now.minus(THROUGHPUT_WINDOW);
        Map<String, ServiceLive> out = new TreeMap<>();
        samples.forEach((service, deque) -> {
            deque.removeIf(s -> s.at().isBefore(cutoff));
            List<Sample> current = new ArrayList<>(deque);
            long last5 = current.stream().filter(s -> !s.at().isBefore(recent)).count();
            long failures = current.stream().filter(s -> !s.success()).count();
            int[] latencies = current.stream().filter(Sample::success).mapToInt(Sample::latencyMs).sorted().toArray();
            Instant last = current.stream().map(Sample::at).max(Instant::compareTo).orElse(null);
            out.put(service, new ServiceLive(service, current.size(), last5, last5 / (double) THROUGHPUT_WINDOW.toMinutes(),
                    failures, current.isEmpty() ? 0 : failures / (double) current.size(),
                    percentile(latencies, 0.50), percentile(latencies, 0.95), last));
        });
        return out;
    }

    static Integer percentile(int[] sorted, double q) {
        if (sorted.length == 0) {
            return null;
        }
        int index = (int) Math.ceil(q * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(index, sorted.length - 1))];
    }
}
