package edu.cmpe272.portal.messaging;

import edu.cmpe272.portal.auth.AuthenticatedUser;
import edu.cmpe272.portal.messaging.PatientMessageController.PatientStatus;
import edu.cmpe272.portal.messaging.PatientMessageController.SubmitResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.time.OffsetDateTime;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Seeding only, and present only when {@code DEMO_MODE=true}. It runs the same PHI-first intake as
 * {@code POST /api/messages} but lets the seed script tag provenance (portalpal or synthetic) and backdate
 * {@code submitted_at} up to 30 days, so the trend charts have history.
 */
@RestController
@RequestMapping("/api/demo/messages")
@Tag(name = "Demo seeding (DEMO_MODE only)")
@PreAuthorize("hasRole('PATIENT')")
@ConditionalOnProperty(name = "app.demo.enabled", havingValue = "true")
public class DemoMessageController {

    static final Duration MAX_BACKFILL = Duration.ofDays(30);

    private final IntakeService intake;

    public DemoMessageController(IntakeService intake) {
        this.intake = intake;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Submit a seeded message with a source tag and optional backdated time")
    public SubmitResponse submit(@AuthenticationPrincipal AuthenticatedUser patient,
                                 @Valid @RequestBody DemoSubmitRequest request) {
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime at = request.submittedAt() == null ? now : request.submittedAt();
        if (at.isAfter(now.plusMinutes(1)) || at.isBefore(now.minus(MAX_BACKFILL))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "submittedAt must be within the last 30 days");
        }
        if (request.source() == MessageSource.MANUAL) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "use POST /api/messages for manual messages");
        }
        Message m = intake.submit(patient, request.text(), request.source(), at);
        return new SubmitResponse(m.getId(), PatientStatus.of(m), m.getSubmittedAt());
    }

    public record DemoSubmitRequest(
            @NotBlank @Size(max = PatientMessageController.MAX_TEXT) String text,
            @NotNull MessageSource source,
            OffsetDateTime submittedAt) {

        @Override
        public String toString() {
            return "DemoSubmitRequest[" + source + ", length=" + (text == null ? 0 : text.length()) + "]";
        }
    }
}
