package edu.cmpe272.portal.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cmpe272.portal.audit.AuditAction;
import edu.cmpe272.portal.audit.AuditService;
import edu.cmpe272.portal.auth.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/** JSON 401/403 responses for the security chain; 403s are written to the audit log. */
@Component
public class SecurityErrorHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper objectMapper;
    private final AuditService audit;

    public SecurityErrorHandlers(ObjectMapper objectMapper, AuditService audit) {
        this.objectMapper = objectMapper;
        this.audit = audit;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        write(response, ApiError.of(401, "Unauthorized", "Authentication required", request.getRequestURI()));
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        write(response, forbidden(request));
    }

    /** Records the denial and builds the 403 body. Also used for denials raised inside controllers. */
    public ApiError forbidden(HttpServletRequest request) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthenticatedUser user) {
            audit.record(user, AuditAction.ACCESS_DENIED, "endpoint",
                    truncate(request.getMethod() + " " + request.getRequestURI()), null);
        }
        return ApiError.of(403, "Forbidden", "Your role does not permit this action", request.getRequestURI());
    }

    private void write(HttpServletResponse response, ApiError body) throws IOException {
        response.setStatus(body.status());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), body);
    }

    private static String truncate(String s) {
        return s.length() <= 64 ? s : s.substring(0, 64);
    }
}
