package edu.cmpe272.portal.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import edu.cmpe272.portal.auth.AuthenticatedUser;
import edu.cmpe272.portal.fhir.FhirPatientLinker;
import edu.cmpe272.portal.user.Role;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

class IntakeServiceTest {

    private static final String ORIGINAL = "This is Maria Lopez, DOB 1962-03-14. I need a refill.";
    private static final String REDACTED = "This is <PERSON>, DOB <DATE_OF_BIRTH>. I need a refill.";

    private final MessageRepository messages = mock(MessageRepository.class);
    private final MessageOriginalRepository originals = mock(MessageOriginalRepository.class);
    private final PhiClient phi = mock(PhiClient.class);
    private final RedactedMessagePublisher publisher = mock(RedactedMessagePublisher.class);
    private final ServiceEventPublisher events = mock(ServiceEventPublisher.class);
    private final FhirPatientLinker fhirPatients = mock(FhirPatientLinker.class);
    private final AuthenticatedUser patient = new AuthenticatedUser(UUID.randomUUID(), "maria.lopez", Role.PATIENT);
    private IntakeService intake;

    @BeforeEach
    void setUp() {
        TransactionTemplate tx = mock(TransactionTemplate.class);
        when(tx.execute(any())).thenAnswer(inv -> inv.<TransactionCallback<?>>getArgument(0)
                .doInTransaction(new SimpleTransactionStatus()));
        org.mockito.Mockito.doAnswer(inv -> {
            inv.<java.util.function.Consumer<org.springframework.transaction.TransactionStatus>>getArgument(0)
                    .accept(new SimpleTransactionStatus());
            return null;
        }).when(tx).executeWithoutResult(any());
        when(messages.findById(any())).thenReturn(Optional.empty());
        when(fhirPatients.resolve(any(), any())).thenReturn(Optional.of("pat-482913"));
        intake = new IntakeService(messages, originals, phi, publisher, events, fhirPatients, tx);
    }

    @Test
    void originalIsStoredRestrictedAndOnlyRedactedTextIsPublished() {
        when(phi.redact(any(), eq(ORIGINAL))).thenAnswer(inv ->
                new PhiClient.PhiResult(inv.getArgument(0), REDACTED, 2, Map.of("PERSON", 1, "DATE_OF_BIRTH", 1)));
        when(publisher.publish(any())).thenReturn(true);
        when(messages.markPublished(any(), any())).thenReturn(1);

        Message m = intake.submit(patient, ORIGINAL);

        var original = ArgumentCaptor.forClass(MessageOriginal.class);
        verify(originals).save(original.capture());
        assertThat(original.getValue().getOriginalText()).isEqualTo(ORIGINAL);

        var published = ArgumentCaptor.forClass(Message.class);
        verify(publisher).publish(published.capture());
        assertThat(published.getValue().getRedactedText()).isEqualTo(REDACTED).doesNotContain("Maria");
        assertThat(m.getPhiEntityCount()).isEqualTo(2);
        assertThat(m.getFhirPatientId()).isEqualTo("pat-482913");
        verify(events).emit(eq(m.getId()), eq("phi-detection"), eq("SUCCESS"), anyLong());
    }

    @Test
    void unreachableFhirServerDoesNotBlockIntake() {
        when(phi.redact(any(), any())).thenAnswer(inv -> new PhiClient.PhiResult(inv.getArgument(0), REDACTED, 2, Map.of()));
        when(fhirPatients.resolve(any(), any())).thenReturn(Optional.empty());
        when(publisher.publish(any())).thenReturn(true);
        when(messages.markPublished(any(), any())).thenReturn(1);

        Message m = intake.submit(patient, ORIGINAL);

        assertThat(m.getFhirPatientId()).isNull();
        verify(publisher).publish(any());
    }

    @Test
    void redactionFailureBlocksPublishing() {
        when(phi.redact(any(), any())).thenThrow(new RedactionException(RedactionException.Reason.ERROR, null));

        Message m = intake.submit(patient, ORIGINAL);

        assertThat(m.getStatus()).isEqualTo(MessageStatus.REDACTION_FAILED);
        assertThat(m.getRedactedText()).isNull();
        verify(publisher, never()).publish(any());
        verify(fhirPatients, never()).resolve(any(), any());
        verify(events).emit(eq(m.getId()), eq("phi-detection"), eq("FAILED"), anyLong());
    }

    @Test
    void redactionTimeoutIsReportedAsTimeout() {
        when(phi.redact(any(), any())).thenThrow(new RedactionException(RedactionException.Reason.TIMEOUT, null));

        Message m = intake.submit(patient, ORIGINAL);

        assertThat(m.getStatus()).isEqualTo(MessageStatus.REDACTION_FAILED);
        verify(publisher, never()).publish(any());
        verify(events).emit(eq(m.getId()), eq("phi-detection"), eq("TIMEOUT"), anyLong());
    }

    @Test
    void unacknowledgedPublishLeavesMessageRedactedForTheOutbox() {
        when(phi.redact(any(), any())).thenAnswer(inv -> new PhiClient.PhiResult(inv.getArgument(0), REDACTED, 2, Map.of()));
        when(publisher.publish(any())).thenReturn(false);

        Message m = intake.submit(patient, ORIGINAL);

        assertThat(m.getStatus()).isEqualTo(MessageStatus.REDACTED);
        verify(messages, never()).markPublished(any(), any());
    }
}
