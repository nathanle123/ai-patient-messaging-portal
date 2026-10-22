package edu.cmpe272.portal.analytics;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/** Polls every AI service's GET /health and keeps the latest result for the dashboard. */
@Component
public class ServiceHealthMonitor {

    public record Health(String service, String status, Integer httpStatus, Instant checkedAt) {
    }

    private final AnalyticsProperties properties;
    private final RestClient client;
    private final Map<String, Health> latest = new ConcurrentHashMap<>();

    public ServiceHealthMonitor(AnalyticsProperties properties, RestClient.Builder builder) {
        this.properties = properties;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(1));
        factory.setReadTimeout(Duration.ofSeconds(2));
        this.client = builder.requestFactory(factory).build();
    }

    @Scheduled(fixedDelayString = "${app.analytics.health-interval:10s}", initialDelay = 2000)
    public void check() {
        properties.services().forEach((service, url) -> latest.put(service, probe(service, url)));
    }

    Health probe(String service, String url) {
        try {
            var res = client.get().uri(url + "/health").retrieve().toBodilessEntity();
            return new Health(service, "up", res.getStatusCode().value(), Instant.now());
        } catch (RestClientResponseException e) {
            return new Health(service, e.getStatusCode().value() == 503 ? "degraded" : "down", e.getStatusCode().value(),
                    Instant.now());
        } catch (Exception e) {
            return new Health(service, "down", null, Instant.now());
        }
    }

    public Map<String, Health> latest() {
        return new TreeMap<>(latest);
    }
}
