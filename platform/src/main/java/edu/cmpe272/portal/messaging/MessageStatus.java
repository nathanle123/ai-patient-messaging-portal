package edu.cmpe272.portal.messaging;

public enum MessageStatus {
    /** Stored (original text in message_original); redaction not attempted yet. */
    RECEIVED,
    /** Redacted text stored; waiting for Kafka to acknowledge the publish. */
    REDACTED,
    /** The PHI service failed or timed out. Never published. */
    REDACTION_FAILED,
    /** On messages.redacted; AI results arriving. */
    PUBLISHED,
    /** All four AI results persisted. */
    PROCESSED
}
