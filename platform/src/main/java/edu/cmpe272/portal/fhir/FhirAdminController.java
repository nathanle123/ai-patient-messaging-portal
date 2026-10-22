package edu.cmpe272.portal.fhir;

import edu.cmpe272.portal.audit.AuditAction;
import edu.cmpe272.portal.audit.AuditService;
import edu.cmpe272.portal.auth.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Admin view of the FHIR write-back. Counts only. */
@RestController
@RequestMapping("/api/admin/fhir")
@Tag(name = "FHIR write-back (admin)")
@PreAuthorize("hasRole('ADMIN')")
public class FhirAdminController {

    private final FhirWriteBackStore store;
    private final AuditService audit;

    public FhirAdminController(FhirWriteBackStore store, AuditService audit) {
        this.store = store;
        this.audit = audit;
    }

    @GetMapping("/status")
    @Operation(summary = "Communication write-back counts: written, pending, rejected")
    public FhirWriteBackStore.Counts status() {
        return store.counts();
    }

    @PostMapping("/resync")
    @Operation(summary = "Mark every message pending again so the sweeper rewrites it (e.g. after the in-memory "
            + "FHIR mock restarted). Idempotent on the FHIR side: Communications are created conditionally.")
    public Map<String, Integer> resync(@AuthenticationPrincipal AuthenticatedUser admin) {
        int reset = store.resetAll();
        audit.record(admin, AuditAction.FHIR_RESYNC, "message", null, "reset=" + reset);
        return Map.of("reset", reset);
    }
}
