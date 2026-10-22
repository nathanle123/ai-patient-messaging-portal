package edu.cmpe272.portal.fhir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import edu.cmpe272.portal.messaging.ServiceEventPublisher;
import edu.cmpe272.portal.user.AppUser;
import edu.cmpe272.portal.user.AppUserRepository;
import edu.cmpe272.portal.user.Role;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FhirPatientLinkerTest {

    private final FhirGateway gateway = mock(FhirGateway.class);
    private final AppUserRepository users = mock(AppUserRepository.class);
    private final ServiceEventPublisher events = mock(ServiceEventPublisher.class);
    private final AppUser maria = new AppUser("maria.lopez", "hash", Role.PATIENT, "Maria Lopez", "MRN-482913");
    private final UUID messageId = UUID.randomUUID();
    private Instant now = Instant.parse("2026-10-20T10:00:00Z");

    private FhirPatientLinker linker(boolean enabled) {
        Clock clock = new Clock() {
            @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return now; }
        };
        return new FhirPatientLinker(new FhirProperties(enabled, "http://fhir/fhir", Duration.ofSeconds(1),
                Duration.ofSeconds(3), Duration.ofMinutes(10), 50), gateway, users, events, clock);
    }

    @BeforeEach
    void setUp() {
        when(users.findById(maria.getId())).thenReturn(Optional.of(maria));
    }

    @Test
    void looksUpByMrnAndCachesUntilTheTtl() {
        when(gateway.findPatientIdByMrn("MRN-482913")).thenReturn(Optional.of("pat-482913"));
        FhirPatientLinker linker = linker(true);

        assertThat(linker.resolve(maria.getId(), messageId)).contains("pat-482913");
        assertThat(linker.resolve(maria.getId(), messageId)).contains("pat-482913");
        verify(gateway, times(1)).findPatientIdByMrn("MRN-482913");
        verify(events, times(1)).emit(eq(messageId), eq("fhir"), eq("SUCCESS"), anyLong());

        now = now.plus(Duration.ofMinutes(11));
        linker.resolve(maria.getId(), messageId);
        verify(gateway, times(2)).findPatientIdByMrn("MRN-482913");
    }

    @Test
    void outageIsEmptyForIntakeButThrowsForWriteBack() {
        when(gateway.findPatientIdByMrn(any())).thenThrow(new FhirException("FhirClientConnectionException", null, true));
        FhirPatientLinker linker = linker(true);

        assertThat(linker.resolve(maria.getId(), messageId)).isEmpty();
        assertThatThrownBy(() -> linker.lookup(maria.getId(), messageId)).isInstanceOf(FhirException.class);
        verify(events, times(2)).emit(eq(messageId), eq("fhir"), eq("FAILED"), anyLong());
    }

    @Test
    void unknownPatientIsNotCached() {
        when(gateway.findPatientIdByMrn(any())).thenReturn(Optional.empty());
        FhirPatientLinker linker = linker(true);

        assertThat(linker.lookup(maria.getId(), messageId)).isEmpty();
        assertThat(linker.lookup(maria.getId(), messageId)).isEmpty();
        verify(gateway, times(2)).findPatientIdByMrn(any());
    }

    @Test
    void disabledNeverCallsTheServer() {
        assertThat(linker(false).resolve(maria.getId(), messageId)).isEmpty();
        verify(gateway, never()).findPatientIdByMrn(any());
        verify(users, never()).findById(any());
    }
}
