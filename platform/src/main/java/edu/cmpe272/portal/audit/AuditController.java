package edu.cmpe272.portal.audit;

import edu.cmpe272.portal.auth.AuthenticatedUser;
import edu.cmpe272.portal.user.Role;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/admin/audit-log")
@Tag(name = "Audit log")
public class AuditController {

    private final AuditService auditService;

    public AuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Page through the audit log, newest first (admin only)")
    public AuditPage list(@AuthenticationPrincipal AuthenticatedUser admin,
                          @RequestParam(required = false) AuditAction action,
                          @RequestParam(defaultValue = "0") @Min(0) int page,
                          @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        auditService.record(admin, AuditAction.VIEW_AUDIT_LOG, "audit_log", null, null);
        Page<AuditLogEntry> result = auditService.search(action,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "occurredAt", "id")));
        return new AuditPage(result.map(AuditEntryView::of).getContent(), result.getNumber(),
                result.getSize(), result.getTotalElements());
    }

    public record AuditEntryView(Long id, OffsetDateTime occurredAt, UUID actorUserId, String actorUsername,
                                 Role actorRole, AuditAction action, String targetType, String targetId,
                                 String detail) {

        static AuditEntryView of(AuditLogEntry e) {
            return new AuditEntryView(e.getId(), e.getOccurredAt(), e.getActorUserId(), e.getActorUsername(),
                    e.getActorRole(), e.getAction(), e.getTargetType(), e.getTargetId(), e.getDetail());
        }
    }

    public record AuditPage(List<AuditEntryView> items, int page, int size, long totalItems) {
    }
}
