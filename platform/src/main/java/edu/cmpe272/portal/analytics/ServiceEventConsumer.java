package edu.cmpe272.portal.analytics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cmpe272.portal.results.InvalidResultException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Set;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code service.events} ({event_id, message_id, service, status, latency_ms, timestamp}):
 * persists each event to {@code service_event} (idempotent on event_id) and feeds {@link LiveMetrics}.
 * After a restart, live metrics are warmed from the table so the dashboard doesn't start empty.
 */
@Component
public class ServiceEventConsumer {

    static final Set<String> SERVICES = Set.of("phi-detection", "urgency", "routing", "summarization", "search", "fhir");
    static final Set<String> STATUSES = Set.of("SUCCESS", "FAILED", "TIMEOUT");

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final LiveMetrics live;
    private final AnalyticsProperties properties;

    public ServiceEventConsumer(JdbcTemplate jdbc, ObjectMapper json, LiveMetrics live, AnalyticsProperties properties) {
        this.jdbc = jdbc;
        this.json = json;
        this.live = live;
        this.properties = properties;
    }

    @KafkaListener(id = "service-events", topics = "service.events", groupId = "platform-events")
    public void onEvent(ConsumerRecord<String, String> record) {
        ServiceEvent e = parse(record.value());
        int inserted = jdbc.update("""
                INSERT INTO service_event (event_id, message_id, service, status, latency_ms, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (event_id) DO NOTHING
                """, e.eventId(), e.messageId(), e.service(), e.status(), e.latencyMs(), Timestamp.from(e.at()));
        if (inserted == 1) {
            live.record(e.service(), "SUCCESS".equals(e.status()), e.latencyMs(), e.at());
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmLiveMetrics() {
        jdbc.query("SELECT service, status, latency_ms, occurred_at FROM service_event WHERE occurred_at >= ? ORDER BY occurred_at",
                rs -> {
                    live.record(rs.getString("service"), "SUCCESS".equals(rs.getString("status")), rs.getInt("latency_ms"),
                            rs.getTimestamp("occurred_at").toInstant());
                }, Timestamp.from(Instant.now().minus(properties.liveWindow())));
    }

    record ServiceEvent(UUID eventId, UUID messageId, String service, String status, int latencyMs, Instant at) {
    }

    ServiceEvent parse(String value) {
        JsonNode n;
        try {
            n = json.readTree(value);
        } catch (Exception ex) {
            throw new InvalidResultException("service event is not JSON");
        }
        try {
            UUID eventId = UUID.fromString(n.path("event_id").asText());
            String mid = n.path("message_id").asText(null);
            UUID messageId = mid == null || mid.isEmpty() || "null".equals(mid) ? null : UUID.fromString(mid);
            String service = n.path("service").asText();
            String status = n.path("status").asText();
            int latency = n.path("latency_ms").asInt(-1);
            Instant at = Instant.parse(n.path("timestamp").asText());
            if (!SERVICES.contains(service) || !STATUSES.contains(status) || latency < 0) {
                throw new InvalidResultException("service event has unknown service, status, or latency");
            }
            return new ServiceEvent(eventId, messageId, service, status, latency, at);
        } catch (IllegalArgumentException | DateTimeParseException ex) {
            throw new InvalidResultException("service event has a malformed field");
        }
    }
}
