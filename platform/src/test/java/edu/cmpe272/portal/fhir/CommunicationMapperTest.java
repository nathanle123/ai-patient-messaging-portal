package edu.cmpe272.portal.fhir;

import static org.assertj.core.api.Assertions.assertThat;

import ca.uhn.fhir.context.FhirContext;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.UUID;
import org.hl7.fhir.r4.model.Communication;
import org.hl7.fhir.r4.model.StringType;
import org.junit.jupiter.api.Test;

class CommunicationMapperTest {

    private static final String ORIGINAL = "This is Maria Lopez, MRN-482913, call (408) 555-0142. Chest pain since 2am.";
    private static final String REDACTED = "This is <PERSON>, <MRN>, call <PHONE_NUMBER>. Chest pain since 2am.";
    private static final FhirContext CTX = FhirContext.forR4Cached();

    private final UUID messageId = UUID.randomUUID();
    private final CommunicationMapper.Source source = new CommunicationMapper.Source(messageId, "pat-482913", REDACTED,
            OffsetDateTime.parse("2026-10-20T09:15:00Z"), OffsetDateTime.parse("2026-10-20T09:15:01Z"), "symptoms",
            0.91234, "MoritzLaurer/deberta-v3-base-zeroshot-v2.0@8e7e5af5", "urgent", "urgency-rules@2026.10-2");

    @Test
    void payloadIsTheRedactedTextOnly() {
        Communication c = CommunicationMapper.toCommunication(source);

        assertThat(c.getPayload()).hasSize(1);
        assertThat(((StringType) c.getPayloadFirstRep().getContent()).getValue()).isEqualTo(REDACTED);
        String json = CTX.newJsonParser().encodeResourceToString(c);
        assertThat(json).contains(REDACTED)
                .doesNotContain("Maria").doesNotContain("MRN-482913").doesNotContain("555-0142");
    }

    @Test
    void sourceHasNoFieldThatCouldCarryTheOriginalText() {
        // Structural guard: the write-back input cannot hold the restricted original.
        assertThat(Arrays.stream(CommunicationMapper.Source.class.getRecordComponents()).map(rc -> rc.getName()))
                .noneMatch(name -> name.toLowerCase().contains("original"))
                .contains("redactedText");
    }

    @Test
    void carriesIdentifiersReferencesAndAiResultsAsExtensions() {
        Communication c = CommunicationMapper.toCommunication(source);

        assertThat(c.getIdentifierFirstRep().getSystem()).isEqualTo("urn:ietf:rfc:3986");
        assertThat(c.getIdentifierFirstRep().getValue()).isEqualTo("urn:uuid:" + messageId);
        assertThat(c.getStatus()).isEqualTo(Communication.CommunicationStatus.COMPLETED);
        assertThat(c.getSubject().getReference()).isEqualTo("Patient/pat-482913");
        assertThat(c.getSender().getReference()).isEqualTo("Patient/pat-482913");
        assertThat(c.getCategoryFirstRep().getCodingFirstRep().getCode()).isEqualTo("symptoms");
        assertThat(c.getCategoryFirstRep().getCodingFirstRep().getSystem()).isEqualTo(CommunicationMapper.CATEGORY_SYSTEM);
        // Urgency is a review flag, deliberately not Communication.priority (which would read as triage).
        assertThat(c.hasPriority()).isFalse();
        assertThat(c.getExtensionByUrl(CommunicationMapper.URGENCY_EXTENSION).getExtensionString("label")).isEqualTo("urgent");
        var routing = c.getExtensionByUrl(CommunicationMapper.ROUTING_EXTENSION);
        assertThat(routing.getExtensionString("category")).isEqualTo("symptoms");
        assertThat(routing.getExtensionByUrl("confidence").getValue().primitiveValue()).isEqualTo("0.912");
        assertThat(c.getNoteFirstRep().getText()).contains("demonstration-only");
        assertThat(c.getSent()).isEqualTo(java.util.Date.from(source.submittedAt().toInstant()));
    }

    @Test
    void roundTripsThroughTheHapiParser() {
        String json = CTX.newJsonParser().encodeResourceToString(CommunicationMapper.toCommunication(source));
        Communication parsed = CTX.newJsonParser().parseResource(Communication.class, json);
        assertThat(parsed.getPayloadFirstRep().getContentStringType().getValue()).isEqualTo(REDACTED);
    }
}
