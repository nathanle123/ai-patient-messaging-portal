package edu.cmpe272.portal.results;

/** A result record that can never be persisted (bad shape or values). Sent to the DLQ without retries. */
public class InvalidResultException extends RuntimeException {

    public InvalidResultException(String reason) {
        super(reason);
    }
}
