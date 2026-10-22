package edu.cmpe272.portal.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cmpe272.portal.results.InvalidResultException;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ServiceEventParseTest {

    private final ServiceEventConsumer consumer = new ServiceEventConsumer(null, new ObjectMapper(),
            new LiveMetrics(Duration.ofMinutes(60)), new AnalyticsProperties("", "", Duration.ofMinutes(60),
            Duration.ofSeconds(10), Map.of()));

    private static String event(String service, String status, int latency, String messageId) {
        return """
                {"event_id":"%s","message_id":%s,"service":"%s","status":"%s","latency_ms":%d,"timestamp":"2026-10-21T10:00:00.123Z"}
                """.formatted(UUID.randomUUID(), messageId, service, status, latency);
    }

    @Test
    void parsesTheFixedSchema() {
        String mid = UUID.randomUUID().toString();
        var e = consumer.parse(event("routing", "SUCCESS", 1200, "\"" + mid + "\""));
        assertThat(e.messageId()).hasToString(mid);
        assertThat(e.latencyMs()).isEqualTo(1200);
    }

    @Test
    void messageIdMayBeNull() {
        assertThat(consumer.parse(event("phi-detection", "TIMEOUT", 5000, "null")).messageId()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"dental", "PHI"})
    void unknownServiceIsRejected(String service) {
        assertThatThrownBy(() -> consumer.parse(event(service, "SUCCESS", 1, "null")))
                .isInstanceOf(InvalidResultException.class);
    }

    @Test
    void badStatusNegativeLatencyAndGarbageAreRejected() {
        assertThatThrownBy(() -> consumer.parse(event("urgency", "MAYBE", 1, "null"))).isInstanceOf(InvalidResultException.class);
        assertThatThrownBy(() -> consumer.parse(event("urgency", "SUCCESS", -1, "null"))).isInstanceOf(InvalidResultException.class);
        assertThatThrownBy(() -> consumer.parse("Maria Lopez")).isInstanceOf(InvalidResultException.class)
                .hasMessageNotContaining("Maria");
    }
}
