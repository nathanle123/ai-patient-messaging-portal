package edu.cmpe272.portal.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes the redacted message to messages.redacted, keyed by message id, and waits for the
 * broker's acknowledgement. The payload is built only from redacted fields; there is no code path
 * from {@link MessageOriginal} to Kafka.
 */
@Component
public class RedactedMessagePublisher {

    static final int SCHEMA_VERSION = 1;
    private static final long ACK_TIMEOUT_SECONDS = 10;

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper json;

    public RedactedMessagePublisher(KafkaTemplate<String, String> kafka, ObjectMapper json) {
        this.kafka = kafka;
        this.json = json;
    }

    /** @return true when Kafka acknowledged the record */
    public boolean publish(Message message) {
        if (message.getRedactedText() == null) {
            throw new IllegalStateException("refusing to publish a message without redacted text");
        }
        try {
            kafka.send(Topics.MESSAGES_REDACTED, message.getId().toString(), payload(message))
                    .get(ACK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            return false;
        }
    }

    String payload(Message message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schema_version", SCHEMA_VERSION);
        body.put("message_id", message.getId().toString());
        body.put("redacted_text", message.getRedactedText());
        body.put("submitted_at", message.getSubmittedAt().toString());
        body.put("published_at", OffsetDateTime.now().toString());
        try {
            return json.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialise message " + message.getId());
        }
    }
}
