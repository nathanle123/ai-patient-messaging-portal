package edu.cmpe272.portal.messaging;

import edu.cmpe272.portal.auth.AuthenticatedUser;
import edu.cmpe272.portal.fhir.FhirPatientLinker;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * PHI-first intake:
 * <ol>
 *   <li>store the message (RECEIVED) and its original text in the restricted table;</li>
 *   <li>call the PHI service synchronously; on failure or timeout mark REDACTION_FAILED and stop;</li>
 *   <li>store the redacted text (REDACTED), linked to the patient's FHIR Patient (looked up by MRN;
 *       if the FHIR server is unreachable the message continues unlinked and write-back links it);</li>
 *   <li>publish the redacted text only, and mark PUBLISHED once Kafka acknowledges. If the publish
 *       fails, the message stays REDACTED and {@link OutboxRepublisher} retries it.</li>
 * </ol>
 * The original is kept even when redaction fails, so nothing a patient wrote is lost; it is still
 * restricted and never leaves the platform.
 */
@Service
public class IntakeService {

    static final String PHI_SERVICE = "phi-detection";
    private static final Logger log = LoggerFactory.getLogger(IntakeService.class);

    private final MessageRepository messages;
    private final MessageOriginalRepository originals;
    private final PhiClient phi;
    private final RedactedMessagePublisher publisher;
    private final ServiceEventPublisher events;
    private final FhirPatientLinker fhirPatients;
    private final TransactionTemplate tx;

    public IntakeService(MessageRepository messages, MessageOriginalRepository originals, PhiClient phi,
                         RedactedMessagePublisher publisher, ServiceEventPublisher events,
                         FhirPatientLinker fhirPatients, TransactionTemplate tx) {
        this.messages = messages;
        this.originals = originals;
        this.phi = phi;
        this.publisher = publisher;
        this.events = events;
        this.fhirPatients = fhirPatients;
        this.tx = tx;
    }

    public Message submit(AuthenticatedUser patient, String text) {
        return submit(patient, text, new Message(patient.id()));
    }

    /** Demo-only variant (see DemoMessageController): provenance tag and a backdated submission time. */
    public Message submit(AuthenticatedUser patient, String text, MessageSource source, OffsetDateTime submittedAt) {
        return submit(patient, text, new Message(patient.id(), source, submittedAt));
    }

    private Message submit(AuthenticatedUser patient, String text, Message message) {
        tx.executeWithoutResult(s -> {
            messages.save(message);
            originals.save(new MessageOriginal(message.getId(), text));
        });

        long started = System.nanoTime();
        PhiClient.PhiResult result;
        try {
            result = phi.redact(message.getId(), text);
        } catch (RedactionException e) {
            events.emit(message.getId(), PHI_SERVICE, e.reason() == RedactionException.Reason.TIMEOUT ? "TIMEOUT" : "FAILED",
                    elapsedMs(started));
            message.redactionFailed();
            tx.executeWithoutResult(s -> messages.save(message));
            log.warn("message {} not published: {} ({})", message.getId(), e.reason(),
                    e.getCause() == null ? "-" : e.getCause().getClass().getSimpleName());
            return message;
        }
        events.emit(message.getId(), PHI_SERVICE, "SUCCESS", elapsedMs(started));

        message.redacted(result.redactedText(), result.phiEntityCount());
        fhirPatients.resolve(patient.id(), message.getId()).ifPresent(message::linkFhirPatient);
        tx.executeWithoutResult(s -> messages.save(message));
        publishAndMark(message);
        return messages.findById(message.getId()).orElse(message);
    }

    /** @return true if published and marked PUBLISHED */
    public boolean publishAndMark(Message message) {
        if (!publisher.publish(message)) {
            log.warn("message {} redacted but not yet published; will retry", message.getId());
            return false;
        }
        Integer updated = tx.execute(s -> messages.markPublished(message.getId(), OffsetDateTime.now()));
        return updated != null && updated == 1;
    }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }
}
