package edu.cmpe272.portal.messaging;

/** Message deliberately excludes the cause's message, which could echo request content. */
public class RedactionException extends RuntimeException {

    public enum Reason { TIMEOUT, ERROR, INVALID_RESPONSE }

    private final Reason reason;

    public RedactionException(Reason reason, Throwable cause) {
        super("PHI redaction failed: " + reason, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
