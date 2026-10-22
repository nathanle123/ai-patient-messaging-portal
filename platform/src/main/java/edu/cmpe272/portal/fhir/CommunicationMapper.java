package edu.cmpe272.portal.fhir;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import org.hl7.fhir.r4.model.CodeType;
import org.hl7.fhir.r4.model.Communication;
import org.hl7.fhir.r4.model.DecimalType;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.StringType;

/**
 * Builds the write-back Communication for a processed message.
 *
 * <p>The input is a {@link Source} read from the {@code message} and {@code ai_result} tables. It has
 * no field for the original text, so the payload can only ever be the redacted text.
 *
 * <p>AI results travel as extensions rather than {@code Communication.priority}: priority would read
 * as clinical triage, and the urgency label is a demonstration-only review flag.
 */
public final class CommunicationMapper {

    public static final String MESSAGE_ID_SYSTEM = "urn:ietf:rfc:3986";
    static final String PROFILE_BASE = "http://cmpe272.example.org/fhir/";
    public static final String CATEGORY_SYSTEM = PROFILE_BASE + "CodeSystem/message-category";
    public static final String URGENCY_EXTENSION = PROFILE_BASE + "StructureDefinition/ai-urgency-flag";
    public static final String ROUTING_EXTENSION = PROFILE_BASE + "StructureDefinition/ai-routing";
    static final String NOTE = "Payload is PHI-redacted portal message text. AI urgency and routing results are "
            + "demonstration-only review flags, not validated clinical triage.";

    static final Map<String, String> CATEGORY_DISPLAY = Map.of(
            "medication_refill", "Medication refill",
            "scheduling", "Scheduling",
            "test_results", "Test results",
            "billing", "Billing",
            "symptoms", "Symptoms",
            "other", "Other");

    /** Everything the Communication is built from; deliberately no original text. */
    public record Source(UUID messageId, String fhirPatientId, String redactedText, OffsetDateTime submittedAt,
                         OffsetDateTime publishedAt, String category, Double routingConfidence, String routingModel,
                         String urgencyLabel, String urgencyModel) {
    }

    private CommunicationMapper() {
    }

    public static String messageIdentifier(UUID messageId) {
        return "urn:uuid:" + messageId;
    }

    public static Communication toCommunication(Source s) {
        Communication c = new Communication();
        c.addIdentifier().setSystem(MESSAGE_ID_SYSTEM).setValue(messageIdentifier(s.messageId()));
        c.setStatus(Communication.CommunicationStatus.COMPLETED);
        c.addCategory().setText(CATEGORY_DISPLAY.getOrDefault(s.category(), s.category()))
                .addCoding().setSystem(CATEGORY_SYSTEM).setCode(s.category())
                .setDisplay(CATEGORY_DISPLAY.getOrDefault(s.category(), s.category()));
        Reference patient = new Reference("Patient/" + s.fhirPatientId());
        c.setSubject(patient);
        c.setSender(patient.copy());
        c.setSent(Date.from(s.submittedAt().toInstant()));
        if (s.publishedAt() != null) {
            c.setReceived(Date.from(s.publishedAt().toInstant()));
        }
        c.addPayload().setContent(new StringType(s.redactedText()));
        c.addNote().setText(NOTE);

        Extension urgency = c.addExtension().setUrl(URGENCY_EXTENSION);
        urgency.addExtension("label", new CodeType(s.urgencyLabel()));
        if (s.urgencyModel() != null) {
            urgency.addExtension("ruleset", new StringType(s.urgencyModel()));
        }
        Extension routing = c.addExtension().setUrl(ROUTING_EXTENSION);
        routing.addExtension("category", new CodeType(s.category()));
        if (s.routingConfidence() != null) {
            routing.addExtension("confidence",
                    new DecimalType(BigDecimal.valueOf(s.routingConfidence()).setScale(3, RoundingMode.HALF_UP)));
        }
        if (s.routingModel() != null) {
            routing.addExtension("model", new StringType(s.routingModel()));
        }
        return c;
    }
}
