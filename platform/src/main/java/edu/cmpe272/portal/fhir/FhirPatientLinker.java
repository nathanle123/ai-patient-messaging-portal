package edu.cmpe272.portal.fhir;

import edu.cmpe272.portal.messaging.ServiceEventPublisher;
import edu.cmpe272.portal.user.AppUser;
import edu.cmpe272.portal.user.AppUserRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Resolves a patient account to its FHIR Patient (search by MRN, the account's link to the EHR).
 * Used on intake and again at write-back if intake could not reach the FHIR server. A failed lookup
 * never blocks intake: the message is processed and linked later.
 */
@Component
public class FhirPatientLinker {

    static final String SERVICE = "fhir";
    private static final Logger log = LoggerFactory.getLogger(FhirPatientLinker.class);

    private record Cached(String patientId, Instant expires) {
    }

    private final FhirProperties properties;
    private final FhirGateway gateway;
    private final AppUserRepository users;
    private final ServiceEventPublisher events;
    private final Clock clock;
    private final Map<UUID, Cached> cache = new ConcurrentHashMap<>();

    @Autowired
    public FhirPatientLinker(FhirProperties properties, FhirGateway gateway, AppUserRepository users,
                             ServiceEventPublisher events) {
        this(properties, gateway, users, events, Clock.systemUTC());
    }

    FhirPatientLinker(FhirProperties properties, FhirGateway gateway, AppUserRepository users,
                      ServiceEventPublisher events, Clock clock) {
        this.properties = properties;
        this.gateway = gateway;
        this.users = users;
        this.events = events;
        this.clock = clock;
    }

    /**
     * For intake: never throws, and an unreachable FHIR server just means "not linked yet".
     *
     * @param messageId only used to correlate the service event
     */
    public Optional<String> resolve(UUID patientUserId, UUID messageId) {
        try {
            return lookup(patientUserId, messageId);
        } catch (FhirException e) {
            return Optional.empty();
        }
    }

    /**
     * @return the Patient id, or empty if FHIR is disabled or no Patient matches the account's MRN
     * @throws FhirException if the FHIR server could not be asked
     */
    public Optional<String> lookup(UUID patientUserId, UUID messageId) {
        if (!properties.enabled()) {
            return Optional.empty();
        }
        Cached hit = cache.get(patientUserId);
        if (hit != null && hit.expires().isAfter(clock.instant())) {
            return Optional.of(hit.patientId());
        }
        Optional<String> mrn = users.findById(patientUserId).map(AppUser::getMrn);
        if (mrn.isEmpty()) {
            return Optional.empty();
        }
        long started = System.nanoTime();
        try {
            Optional<String> id = gateway.findPatientIdByMrn(mrn.get());
            events.emit(messageId, SERVICE, "SUCCESS", (System.nanoTime() - started) / 1_000_000);
            id.ifPresentOrElse(
                    pid -> cache.put(patientUserId, new Cached(pid, clock.instant().plus(properties.patientCacheTtl()))),
                    () -> log.warn("no FHIR Patient for user {}", patientUserId));
            return id;
        } catch (FhirException e) {
            events.emit(messageId, SERVICE, "FAILED", (System.nanoTime() - started) / 1_000_000);
            log.warn("FHIR patient lookup for user {} failed: {}", patientUserId, e.getMessage());
            throw e;
        }
    }
}
