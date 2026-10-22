package edu.cmpe272.portal.common;

import edu.cmpe272.portal.auth.InvalidCredentialsException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Maps exceptions to {@link ApiError}. Logs only the exception class and request path, never
 * exception messages or request bodies, which could contain message text.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final SecurityErrorHandlers securityErrors;

    public GlobalExceptionHandler(SecurityErrorHandlers securityErrors) {
        this.securityErrors = securityErrors;
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    ResponseEntity<ApiError> invalidCredentials(InvalidCredentialsException e, HttpServletRequest request) {
        return respond(ApiError.of(401, "Unauthorized", e.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiError> accessDenied(HttpServletRequest request) {
        return respond(securityErrors.forbidden(request));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> invalidBody(MethodArgumentNotValidException e, HttpServletRequest request) {
        List<ApiError.FieldViolation> fields = e.getBindingResult().getFieldErrors().stream()
                .map(f -> new ApiError.FieldViolation(f.getField(), f.getDefaultMessage()))
                .toList();
        return respond(new ApiError(Instant.now(), 400, "Bad Request", "Validation failed",
                request.getRequestURI(), fields));
    }

    @ExceptionHandler({ConstraintViolationException.class, MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class})
    ResponseEntity<ApiError> badRequest(HttpServletRequest request) {
        return respond(ApiError.of(400, "Bad Request", "Malformed or invalid request", request.getRequestURI()));
    }

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<ApiError> notFoundResource(NotFoundException e, HttpServletRequest request) {
        return respond(ApiError.of(404, "Not Found", e.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(ConflictException.class)
    ResponseEntity<ApiError> conflict(ConflictException e, HttpServletRequest request) {
        return respond(ApiError.of(409, "Conflict", e.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ApiError> responseStatus(ResponseStatusException e, HttpServletRequest request) {
        HttpStatus status = HttpStatus.valueOf(e.getStatusCode().value());
        return respond(ApiError.of(status.value(), status.getReasonPhrase(), e.getReason(), request.getRequestURI()));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiError> notFound(HttpServletRequest request) {
        return respond(ApiError.of(404, "Not Found", "No such resource", request.getRequestURI()));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception e, HttpServletRequest request) {
        log.error("Unhandled {} on {} {}", e.getClass().getName(), request.getMethod(), request.getRequestURI());
        return respond(ApiError.of(500, "Internal Server Error", "Unexpected error", request.getRequestURI()));
    }

    private static ResponseEntity<ApiError> respond(ApiError body) {
        return ResponseEntity.status(HttpStatus.valueOf(body.status())).body(body);
    }
}
