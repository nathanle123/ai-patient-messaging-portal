package edu.cmpe272.portal.messaging;

/** Where a message's text came from (column message.source). */
public enum MessageSource {
    /** The patient portal UI or the normal API. */
    MANUAL,
    /** PortalPal-AI Hugging Face dataset text, submitted by the seed script. */
    PORTALPAL,
    /** Labelled template messages for non-symptom categories, submitted by the seed script. */
    SYNTHETIC;

    public String dbValue() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
