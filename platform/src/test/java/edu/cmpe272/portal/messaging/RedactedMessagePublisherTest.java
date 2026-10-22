package edu.cmpe272.portal.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

class RedactedMessagePublisherTest {

    @SuppressWarnings("unchecked")
    private final RedactedMessagePublisher publisher =
            new RedactedMessagePublisher(mock(KafkaTemplate.class), new ObjectMapper());

    @Test
    void payloadHasOnlyRedactedFieldsAndNoPatientIdentity() throws Exception {
        Message m = new Message(UUID.randomUUID());
        m.redacted("Hi, this is <PERSON>.", 1);

        var node = new ObjectMapper().readTree(publisher.payload(m));

        assertThat(node.fieldNames()).toIterable()
                .containsExactly("schema_version", "message_id", "redacted_text", "submitted_at", "published_at");
        assertThat(node.path("redacted_text").asText()).isEqualTo("Hi, this is <PERSON>.");
    }

    @Test
    void refusesToPublishWithoutRedactedText() {
        assertThatThrownBy(() -> publisher.publish(new Message(UUID.randomUUID())))
                .isInstanceOf(IllegalStateException.class);
    }
}
