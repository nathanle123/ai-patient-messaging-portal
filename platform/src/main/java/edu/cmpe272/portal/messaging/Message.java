package edu.cmpe272.portal.messaging;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A portal message. Holds redacted text only; the original lives in {@link MessageOriginal}.
 * AI-derived columns (category, urgency) are written by the result consumer via SQL.
 */
@Entity
@Table(name = "message")
public class Message {

    @Id
    private UUID id;

    @Column(name = "patient_id", nullable = false, updatable = false)
    private UUID patientId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private MessageStatus status;

    @Column(name = "redacted_text")
    private String redactedText;

    @Column(name = "phi_entity_count")
    private Integer phiEntityCount;

    @Column(nullable = false, updatable = false, length = 16)
    private MessageSource source;

    @Column(name = "submitted_at", nullable = false, updatable = false)
    private OffsetDateTime submittedAt;

    @Column(name = "published_at")
    private OffsetDateTime publishedAt;

    @Column(name = "reviewed_at")
    private OffsetDateTime reviewedAt;

    @Column(name = "reviewed_by")
    private UUID reviewedBy;

    /** Logical id of the FHIR Patient, resolved on intake by MRN (null if FHIR was unreachable). */
    @Column(name = "fhir_patient_id", length = 64)
    private String fhirPatientId;

    protected Message() {
    }

    public Message(UUID patientId) {
        this(patientId, MessageSource.MANUAL, OffsetDateTime.now());
    }

    public Message(UUID patientId, MessageSource source, OffsetDateTime submittedAt) {
        this.id = UUID.randomUUID();
        this.patientId = patientId;
        this.source = source;
        this.status = MessageStatus.RECEIVED;
        this.submittedAt = submittedAt;
    }

    public void redacted(String redactedText, int phiEntityCount) {
        this.redactedText = redactedText;
        this.phiEntityCount = phiEntityCount;
        this.status = MessageStatus.REDACTED;
    }

    public void linkFhirPatient(String fhirPatientId) {
        this.fhirPatientId = fhirPatientId;
    }

    public void redactionFailed() {
        this.status = MessageStatus.REDACTION_FAILED;
    }

    public UUID getId() { return id; }
    public UUID getPatientId() { return patientId; }
    public MessageStatus getStatus() { return status; }
    public String getRedactedText() { return redactedText; }
    public Integer getPhiEntityCount() { return phiEntityCount; }
    public MessageSource getSource() { return source; }
    public OffsetDateTime getSubmittedAt() { return submittedAt; }
    public OffsetDateTime getPublishedAt() { return publishedAt; }
    public OffsetDateTime getReviewedAt() { return reviewedAt; }
    public UUID getReviewedBy() { return reviewedBy; }
    public String getFhirPatientId() { return fhirPatientId; }
}
