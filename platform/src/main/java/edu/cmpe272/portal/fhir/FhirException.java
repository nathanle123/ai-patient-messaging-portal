package edu.cmpe272.portal.fhir;

/**
 * A FHIR call failed. Holds no response body or payload, only what is safe to log.
 *
 * @param retryable false for a 4xx rejection (retrying the same resource cannot succeed)
 */
public class FhirException extends RuntimeException {

    private final Integer httpStatus;
    private final boolean retryable;

    public FhirException(String errorClass, Integer httpStatus, boolean retryable) {
        super(errorClass + (httpStatus == null ? "" : " (HTTP " + httpStatus + ")"), null, false, false);
        this.httpStatus = httpStatus;
        this.retryable = retryable;
    }

    public Integer httpStatus() {
        return httpStatus;
    }

    public boolean retryable() {
        return retryable;
    }
}
