package edu.cmpe272.portal.messaging;

import edu.cmpe272.portal.audit.AuditAction;
import edu.cmpe272.portal.audit.AuditService;
import edu.cmpe272.portal.auth.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/messages")
@Tag(name = "Patient messages")
@PreAuthorize("hasRole('PATIENT')")
public class PatientMessageController {

    static final int MAX_TEXT = 5000;

    private final IntakeService intake;
    private final MessageRepository messages;
    private final MessageOriginalRepository originals;
    private final AuditService audit;

    public PatientMessageController(IntakeService intake, MessageRepository messages,
                                    MessageOriginalRepository originals, AuditService audit) {
        this.intake = intake;
        this.messages = messages;
        this.originals = originals;
        this.audit = audit;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Send a message to the care team (PHI is redacted before any further processing)")
    public SubmitResponse submit(@AuthenticationPrincipal AuthenticatedUser patient,
                                 @Valid @RequestBody SubmitRequest request) {
        Message m = intake.submit(patient, request.text());
        return new SubmitResponse(m.getId(), PatientStatus.of(m), m.getSubmittedAt());
    }

    @GetMapping
    @Operation(summary = "List my messages (newest first). Reading them is recorded in the audit log.")
    public List<MyMessage> mine(@AuthenticationPrincipal AuthenticatedUser patient) {
        List<Message> mine = messages.findTop50ByPatientIdOrderBySubmittedAtDesc(patient.id());
        Map<UUID, String> text = originals.findByMessageIdIn(mine.stream().map(Message::getId).toList()).stream()
                .collect(Collectors.toMap(MessageOriginal::getMessageId, MessageOriginal::getOriginalText));
        if (!mine.isEmpty()) {
            audit.record(patient, AuditAction.VIEW_ORIGINAL_MESSAGE, "own_messages", patient.id().toString(),
                    "count=" + mine.size());
        }
        return mine.stream()
                .map(m -> new MyMessage(m.getId(), text.get(m.getId()), PatientStatus.of(m), m.getSubmittedAt(),
                        m.getReviewedAt()))
                .toList();
    }

    public record SubmitRequest(@NotBlank @Size(max = MAX_TEXT) String text) {
        @Override
        public String toString() {
            return "SubmitRequest[length=" + (text == null ? 0 : text.length()) + "]";
        }
    }

    public record SubmitResponse(UUID id, PatientStatus status, OffsetDateTime submittedAt) {
    }

    public record MyMessage(UUID id, String text, PatientStatus status, OffsetDateTime submittedAt,
                            OffsetDateTime reviewedAt) {
    }

    /**
     * What patients see; AI flags are clinician-only. A message whose redaction failed still reaches the
     * clinician inbox (flagged, original text only), so it counts as with the care team.
     */
    public enum PatientStatus {
        SENT, WITH_CARE_TEAM, REVIEWED;

        static PatientStatus of(Message m) {
            if (m.getReviewedAt() != null) {
                return REVIEWED;
            }
            return switch (m.getStatus()) {
                case PUBLISHED, PROCESSED, REDACTION_FAILED -> WITH_CARE_TEAM;
                case RECEIVED, REDACTED -> SENT;
            };
        }
    }
}
