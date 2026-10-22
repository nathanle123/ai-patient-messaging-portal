package edu.cmpe272.portal.analytics;

import edu.cmpe272.portal.analytics.AnalyticsAggregator.JobResult;
import edu.cmpe272.portal.analytics.AnalyticsDtos.ServiceStatus;
import edu.cmpe272.portal.analytics.AnalyticsDtos.Summary;
import edu.cmpe272.portal.analytics.AnalyticsDtos.Technical;
import edu.cmpe272.portal.analytics.AnalyticsDtos.Trends;
import edu.cmpe272.portal.fhir.FhirWriteBackStore;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Admin analytics. Aggregates and timings only; no message text is reachable from here. */
@RestController
@Validated
@RequestMapping("/api/admin/analytics")
@Tag(name = "Analytics (admin)")
@PreAuthorize("hasRole('ADMIN')")
public class AnalyticsController {

    private final AnalyticsQueries queries;
    private final AnalyticsAggregator aggregator;
    private final LiveMetrics live;
    private final ServiceHealthMonitor health;
    private final KafkaInsights kafka;
    private final AnalyticsProperties properties;
    private final FhirWriteBackStore fhir;

    public AnalyticsController(AnalyticsQueries queries, AnalyticsAggregator aggregator, LiveMetrics live,
                               ServiceHealthMonitor health, KafkaInsights kafka, AnalyticsProperties properties,
                               FhirWriteBackStore fhir) {
        this.queries = queries;
        this.aggregator = aggregator;
        this.live = live;
        this.health = health;
        this.kafka = kafka;
        this.properties = properties;
        this.fhir = fhir;
    }

    @GetMapping("/summary")
    @Operation(summary = "Operational metrics: volume by category and urgency, unreviewed urgent, review times")
    public Summary summary(@RequestParam(defaultValue = "all") String source) {
        return queries.summary(checked(source));
    }

    @GetMapping("/trends")
    @Operation(summary = "Historical trends from the scheduled aggregation (UTC hourly or daily buckets)")
    public Trends trends(@RequestParam(defaultValue = "hourly") String granularity,
                         @RequestParam(defaultValue = "all") String source,
                         @RequestParam(defaultValue = "48") @Min(1) @Max(24 * 31) int hours,
                         @RequestParam(defaultValue = "14") @Min(1) @Max(90) int days) {
        String s = checked(source);
        return switch (granularity) {
            case "hourly" -> new Trends("hourly", s, queries.hourly(s, hours), queries.serviceHourly(hours),
                    queries.lastAggregation("hourly"));
            case "daily" -> new Trends("daily", s, queries.daily(s, days), List.of(), queries.lastAggregation("daily"));
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "granularity must be hourly or daily");
        };
    }

    @GetMapping("/technical")
    @Operation(summary = "Live technical metrics from service.events, service health, consumer lag, DLQ depth")
    public Technical technical() {
        Map<String, LiveMetrics.ServiceLive> metrics = live.snapshot(Instant.now());
        Map<String, ServiceHealthMonitor.Health> healthNow = health.latest();
        KafkaInsights.Snapshot k = kafka.snapshot();
        TreeSet<String> names = new TreeSet<>(properties.services().keySet());
        names.addAll(metrics.keySet());
        List<ServiceStatus> services = new ArrayList<>();
        for (String name : names) {
            var h = healthNow.get(name);
            services.add(new ServiceStatus(name, h == null ? "unknown" : h.status(), h == null ? null : h.httpStatus(),
                    h == null ? null : h.checkedAt(), metrics.get(name), k.consumerLag().get(name)));
        }
        return new Technical(services, queries.pipeline(), k.available(), k.consumerLag(), k.dlqDepth(),
                fhir.counts(), Instant.now());
    }

    @PostMapping("/aggregate")
    @Operation(summary = "Run the hourly and daily aggregation jobs now (otherwise scheduled)")
    public List<JobResult> aggregate() {
        return List.of(aggregator.hourly(Instant.now().minus(AnalyticsAggregator.HOURLY_WINDOW), "manual"),
                aggregator.daily(LocalDate.now(ZoneOffset.UTC).minusDays(AnalyticsAggregator.DAILY_WINDOW_DAYS), "manual"));
    }

    private static String checked(String source) {
        if (!AnalyticsQueries.SOURCES.contains(source)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "source must be all, manual, portalpal or synthetic");
        }
        return source;
    }
}
