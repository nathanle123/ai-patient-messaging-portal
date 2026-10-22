package edu.cmpe272.portal.messaging;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Restricted: unredacted text. Read only through audited endpoints. */
@Entity
@Table(name = "message_original")
public class MessageOriginal {

    @Id
    @Column(name = "message_id")
    private UUID messageId;

    @Column(name = "original_text", nullable = false, updatable = false)
    private String originalText;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected MessageOriginal() {
    }

    public MessageOriginal(UUID messageId, String originalText) {
        this.messageId = messageId;
        this.originalText = originalText;
        this.createdAt = OffsetDateTime.now();
    }

    public UUID getMessageId() { return messageId; }
    public String getOriginalText() { return originalText; }

    /** Never print the text. */
    @Override
    public String toString() {
        return "MessageOriginal[" + messageId + "]";
    }
}
