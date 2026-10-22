package edu.cmpe272.portal.inbox;

import edu.cmpe272.portal.audit.AuditAction;
import edu.cmpe272.portal.audit.AuditService;
import edu.cmpe272.portal.auth.AuthenticatedUser;
import edu.cmpe272.portal.common.ConflictException;
import edu.cmpe272.portal.common.NotFoundException;
import edu.cmpe272.portal.inbox.InboxDtos.InboxPage;
import edu.cmpe272.portal.inbox.InboxDtos.MessageDetail;
import edu.cmpe272.portal.inbox.InboxDtos.OriginalText;
import edu.cmpe272.portal.messaging.MessageOriginalRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/clinician")
@Tag(name = "Clinician inbox")
@PreAuthorize("hasRole('CLINICIAN')")
public class ClinicianController {

    private final InboxQueries queries;
    private final MessageOriginalRepository originals;
    private final AuditService audit;
    private final SimilarityProperties similarity;

    public ClinicianController(InboxQueries queries, MessageOriginalRepository originals, AuditService audit,
                               SimilarityProperties similarity) {
        this.queries = queries;
        this.originals = originals;
        this.audit = audit;
        this.similarity = similarity;
    }

    @GetMapping("/inbox")
    @Operation(summary = "Inbox: unreviewed first, then urgent → soon → routine → unclassified, then oldest first")
    public InboxPage inbox(@RequestParam(defaultValue = "UNREVIEWED") InboxQueries.View view,
                           @RequestParam(defaultValue = "0") @Min(0) int page,
                           @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        return new InboxPage(queries.list(view, page, size), queries.counts(), page, size, queries.count(view));
    }

    @GetMapping("/messages/{id}")
    @Operation(summary = "Redacted message with all AI results and similar messages (no original text)")
    public MessageDetail detail(@PathVariable UUID id) {
        return queries.detail(id, similarity.threshold()).orElseThrow(() -> new NotFoundException("message"));
    }

    @GetMapping("/messages/{id}/original")
    @Operation(summary = "Unredacted original text. Every call is recorded in the audit log.")
    public OriginalText original(@AuthenticationPrincipal AuthenticatedUser clinician, @PathVariable UUID id) {
        var original = originals.findById(id).filter(o -> queries.exists(id))
                .orElseThrow(() -> new NotFoundException("message"));
        audit.record(clinician, AuditAction.VIEW_ORIGINAL_MESSAGE, "message", id.toString(), null);
        return new OriginalText(id, original.getOriginalText());
    }

    @PostMapping("/messages/{id}/review")
    @Operation(summary = "Mark a message reviewed (recorded in the audit log)")
    public MessageDetail review(@AuthenticationPrincipal AuthenticatedUser clinician, @PathVariable UUID id) {
        if (queries.markReviewed(id, clinician.id()) == 0) {
            if (!queries.exists(id)) {
                throw new NotFoundException("message");
            }
            throw new ConflictException("Message already reviewed");
        }
        audit.record(clinician, AuditAction.REVIEW_MESSAGE, "message", id.toString(), null);
        return detail(id);
    }
}
