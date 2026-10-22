package edu.cmpe272.portal.fhir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import edu.cmpe272.portal.fhir.FhirWriteBack.Outcome;
import edu.cmpe272.portal.messaging.ServiceEventPublisher;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.hl7.fhir.r4.model.Communication;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class FhirWriteBackTest {

    private static final String REDACTED = "Refill for <PERSON> please, my MRN is <MRN>.";

    private final FhirWriteBackStore store = mock(FhirWriteBackStore.class);
    private final FhirPatientLinker linker = mock(FhirPatientLinker.class);
    private final FhirGateway gateway = mock(FhirGateway.class);
    private final ServiceEventPublisher events = mock(ServiceEventPublisher.class);
    private final UUID messageId = UUID.randomUUID();
    private final UUID patientUser = UUID.randomUUID();

    private FhirWriteBack writeBack(boolean enabled) {
        return new FhirWriteBack(new FhirProperties(enabled, "http://fhir/fhir", Duration.ofSeconds(1), Duration.ofSeconds(3),
                Duration.ofMinutes(10), 50), store, linker, gateway, events);
    }

    private FhirWriteBackStore.Pending pending(String fhirPatientId) {
        return new FhirWriteBackStore.Pending(messageId, patientUser, new CommunicationMapper.Source(messageId, fhirPatientId,
                REDACTED, OffsetDateTime.now(), OffsetDateTime.now(), "medication_refill", 0.88, "deberta", "routine", "rules"));
    }

    @Test
    void notReadyUntilBothRoutingAndUrgencyAreStored() {
        when(store.findReady(messageId)).thenReturn(Optional.empty());

        assertThat(writeBack(true).writeIfReady(messageId)).isEqualTo(Outcome.NOT_READY);
        verify(gateway, never()).createCommunication(any());
    }

    @Test
    void writesRedactedCommunicationAndRecordsTheId() {
        when(store.findReady(messageId)).thenReturn(Optional.of(pending("pat-1")));
        when(gateway.createCommunication(any())).thenReturn(new FhirGateway.WriteResult("c-1", true));

        assertThat(writeBack(true).writeIfReady(messageId)).isEqualTo(Outcome.WRITTEN);

        var sent = ArgumentCaptor.forClass(Communication.class);
        verify(gateway).createCommunication(sent.capture());
        assertThat(sent.getValue().getPayloadFirstRep().getContentStringType().getValue()).isEqualTo(REDACTED);
        verify(store).markWritten(messageId, "pat-1", "c-1");
        verify(events).emit(eq(messageId), eq("fhir"), eq("SUCCESS"), anyLong());
        verify(linker, never()).lookup(any(), any());
    }

    @Test
    void linksThePatientAtWriteBackIfIntakeCouldNot() {
        when(store.findReady(messageId)).thenReturn(Optional.of(pending(null)));
        when(linker.lookup(patientUser, messageId)).thenReturn(Optional.of("pat-9"));
        when(gateway.createCommunication(any())).thenReturn(new FhirGateway.WriteResult("c-9", true));

        assertThat(writeBack(true).writeIfReady(messageId)).isEqualTo(Outcome.WRITTEN);
        verify(store).markWritten(messageId, "pat-9", "c-9");
    }

    @Test
    void missingPatientIsRejectedNotRetriedForever() {
        when(store.findReady(messageId)).thenReturn(Optional.of(pending(null)));
        when(linker.lookup(patientUser, messageId)).thenReturn(Optional.empty());

        assertThat(writeBack(true).writeIfReady(messageId)).isEqualTo(Outcome.REJECTED);
        verify(store).markRejected(messageId, "PatientNotFound");
        verify(gateway, never()).createCommunication(any());
    }

    @Test
    void unreachableServerLeavesItPending() {
        when(store.findReady(messageId)).thenReturn(Optional.of(pending("pat-1")));
        when(gateway.createCommunication(any())).thenThrow(new FhirException("FhirClientConnectionException", null, true));

        assertThat(writeBack(true).writeIfReady(messageId)).isEqualTo(Outcome.RETRY_LATER);
        verify(store).recordFailedAttempt(messageId, "FhirClientConnectionException");
        verify(store, never()).markRejected(any(), any());
        verify(events).emit(eq(messageId), eq("fhir"), eq("FAILED"), anyLong());
    }

    @Test
    void clientErrorIsPermanent() {
        when(store.findReady(messageId)).thenReturn(Optional.of(pending("pat-1")));
        when(gateway.createCommunication(any())).thenThrow(new FhirException("UnprocessableEntityException", 422, false));

        assertThat(writeBack(true).writeIfReady(messageId)).isEqualTo(Outcome.REJECTED);
        verify(store).markRejected(messageId, "UnprocessableEntityException (HTTP 422)");
    }

    @Test
    void databaseErrorsNeverEscape() {
        when(store.findReady(messageId)).thenThrow(new org.springframework.dao.QueryTimeoutException("x"));

        assertThat(writeBack(true).writeIfReady(messageId)).isEqualTo(Outcome.RETRY_LATER);
    }

    @Test
    void sweepStopsAtTheFirstOutage() {
        UUID other = UUID.randomUUID();
        when(store.readyIds(anyInt())).thenReturn(List.of(messageId, other));
        when(store.findReady(messageId)).thenReturn(Optional.of(pending("pat-1")));
        when(gateway.createCommunication(any())).thenThrow(new FhirException("FhirClientConnectionException", null, true));

        writeBack(true).sweep();

        verify(gateway, times(1)).createCommunication(any());
        verify(store, never()).findReady(other);
    }

    @Test
    void disabledDoesNothing() {
        FhirWriteBack disabled = writeBack(false);
        disabled.sweep();
        disabled.requestWriteBack(messageId);

        verify(store, never()).readyIds(anyInt());
        verify(store, never()).findReady(any());
    }
}
