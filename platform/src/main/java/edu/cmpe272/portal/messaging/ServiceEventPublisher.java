package edu.cmpe272.portal.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/** Emits {event_id, message_id, service, status, latency_ms, timestamp} to service.events. Fire-and-forget. */
@Component
public class ServiceEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(ServiceEventPublisher.class);

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper json;

    public ServiceEventPublisher(KafkaTemplate<String, String> kafka, ObjectMapper json) {
        this.kafka = kafka;
        this.json = json;
    }

    public void emit(UUID messageId, String service, String status, long latencyMs) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("event_id", UUID.randomUUID().toString());
        event.put("message_id", messageId == null ? null : messageId.toString());
        event.put("service", service);
        event.put("status", status);
        event.put("latency_ms", latencyMs);
        event.put("timestamp", Instant.now().toString());
        try {
            kafka.send(Topics.SERVICE_EVENTS, messageId == null ? null : messageId.toString(), json.writeValueAsString(event))
                    .whenComplete((ok, ex) -> {
                        if (ex != null) {
                            log.warn("service event for {} not delivered: {}", messageId, ex.getClass().getSimpleName());
                        }
                    });
        } catch (JsonProcessingException | RuntimeException e) {
            log.warn("service event for {} not sent: {}", messageId, e.getClass().getSimpleName());
        }
    }
}
