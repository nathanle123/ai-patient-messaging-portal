package edu.cmpe272.portal.audit;

public enum AuditAction {
    LOGIN_SUCCESS,
    LOGIN_FAILURE,
    ACCESS_DENIED,
    VIEW_AUDIT_LOG,
    /** Phase 3: a clinician or the submitting patient read {@code message_original}. */
    VIEW_ORIGINAL_MESSAGE,
    /** Phase 3: a clinician marked a message reviewed. */
    REVIEW_MESSAGE,
    /** Phase 5: an admin reset the FHIR write-back state so every Communication is rewritten. */
    FHIR_RESYNC
}
