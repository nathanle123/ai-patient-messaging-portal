package edu.cmpe272.portal.results;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ResultEnvelopeTest {

    private final ObjectMapper json = new ObjectMapper();
    private final UUID id = UUID.randomUUID();

    private String envelope(String service, String result) {
        return """
                {"message_id":"%s","service":"%s","model_version":"m@1","processed_at":"2026-10-21T10:00:00.000Z",
                 "result":%s}""".formatted(id, service, result);
    }

    @Test
    void parsesValidUrgencyResult() {
        var e = ResultEnvelope.parse("urgency", envelope("urgency", "{\"label\":\"urgent\",\"flags\":[]}"), json);
        assertThat(e.messageId()).isEqualTo(id);
        assertThat(e.result().path("label").asText()).isEqualTo("urgent");
        assertThat(ResultEnvelope.URGENCY_RANK.get("urgent")).isZero();
    }

    @Test
    void serviceMustMatchTopic() {
        assertThatThrownBy(() -> ResultEnvelope.parse("routing", envelope("urgency", "{\"label\":\"urgent\"}"), json))
                .isInstanceOf(InvalidResultException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"label\":\"panic\"}", "{}", "\"urgent\""})
    void invalidUrgencyIsRejected(String result) {
        assertThatThrownBy(() -> ResultEnvelope.parse("urgency", envelope("urgency", result), json))
                .isInstanceOf(InvalidResultException.class);
    }

    @Test
    void categoryMustMatchSchema() {
        assertThat(ResultEnvelope.parse("routing", envelope("routing", "{\"category\":\"billing\"}"), json)).isNotNull();
        assertThatThrownBy(() -> ResultEnvelope.parse("routing", envelope("routing", "{\"category\":\"dental\"}"), json))
                .isInstanceOf(InvalidResultException.class);
    }

    @Test
    void garbageIsRejectedWithoutEchoingIt() {
        assertThatThrownBy(() -> ResultEnvelope.parse("search", "Maria Lopez {", json))
                .isInstanceOf(InvalidResultException.class)
                .hasMessageNotContaining("Maria");
    }
}
