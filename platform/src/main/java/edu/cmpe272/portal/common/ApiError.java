package edu.cmpe272.portal.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

/**
 * Error body for every non-2xx response. Never includes request values (they may contain PHI);
 * validation errors list field names and constraint messages only.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(Instant timestamp, int status, String error, String message, String path,
                       List<FieldViolation> fieldErrors) {

    public static ApiError of(int status, String error, String message, String path) {
        return new ApiError(Instant.now(), status, error, message, path, null);
    }

    public record FieldViolation(String field, String message) {
    }
}
