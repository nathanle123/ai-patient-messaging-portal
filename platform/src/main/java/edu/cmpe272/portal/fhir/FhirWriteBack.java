package edu.cmpe272.portal.fhir;

import edu.cmpe272.portal.messaging.ServiceEventPublisher;
import jakarta.annotation.PreDestroy;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Writes a processed message back to the FHIR server as a Communication once both its routing and
 * urgency results are stored.
 *
 * <p>Two triggers, both idempotent:
 * <ul>
 *   <li>{@link #requestWriteBack} right after a routing or urgency result is persisted; it runs on a
 *       small pool so the Kafka listener never waits on FHIR;</li>
 *   <li>{@link #sweep}, which picks up anything still pending: a full queue, a FHIR outage, or a
 *       restart between the two.</li>
 * </ul>
 * Duplicates are harmless: the Communication is created conditionally on the message identifier,
 * and the state update only moves a message out of pending once.
 *
 * <p>A 4xx (the server will never accept the resource) or a missing Patient marks the message
 * REJECTED. Connection errors and 5xx leave it pending for the next sweep.
 */
@Service
public class FhirWriteBack {

    public enum Outcome { WRITTEN, NOT_READY, RETRY_LATER, REJECTED }

    private static final Logger log = LoggerFactory.getLogger(FhirWriteBack.class);

    private final FhirProperties properties;
    private final FhirWriteBackStore store;
    private final FhirPatientLinker linker;
    private final FhirGateway gateway;
    private final ServiceEventPublisher events;
    // Bounded: if FHIR is slow the queue fills and further requests are dropped; the sweeper covers them.
    private final ThreadPoolExecutor pool = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1000), Thread.ofVirtual().name("fhir-writeback-", 0).factory(),
            new ThreadPoolExecutor.DiscardPolicy());

    public FhirWriteBack(FhirProperties properties, FhirWriteBackStore store, FhirPatientLinker linker,
                         FhirGateway gateway, ServiceEventPublisher events) {
        this.properties = properties;
        this.store = store;
        this.linker = linker;
        this.gateway = gateway;
        this.events = events;
    }

    /** Fire-and-forget; called by the result consumer after a routing or urgency result is stored. */
    public void requestWriteBack(UUID messageId) {
        if (properties.enabled()) {
            pool.execute(() -> writeIfReady(messageId));
        }
    }

    @Scheduled(fixedDelayString = "${app.fhir.sweep-interval:15s}", initialDelayString = "${app.fhir.sweep-initial-delay:20s}")
    public void sweep() {
        if (!properties.enabled()) {
            return;
        }
        int written = 0;
        for (UUID id : store.readyIds(properties.sweepBatch())) {
            Outcome outcome = writeIfReady(id);
            if (outcome == Outcome.RETRY_LATER) {
                break; // FHIR is unavailable; don't spend a timeout on every pending message
            }
            if (outcome == Outcome.WRITTEN) {
                written++;
            }
        }
        if (written > 0) {
            log.info("fhir sweep: wrote {} Communication(s)", written);
        }
    }

    public Outcome writeIfReady(UUID messageId) {
        try {
            return write(messageId);
        } catch (RuntimeException e) {
            // Database trouble etc.: never let it escape into the Kafka listener or the scheduler.
            log.warn("fhir write-back for {} failed: {}", messageId, e.getClass().getSimpleName());
            return Outcome.RETRY_LATER;
        }
    }

    private Outcome write(UUID messageId) {
        Optional<FhirWriteBackStore.Pending> ready = store.findReady(messageId);
        if (ready.isEmpty()) {
            return Outcome.NOT_READY;
        }
        FhirWriteBackStore.Pending pending = ready.get();
        CommunicationMapper.Source source = pending.source();
        if (source.fhirPatientId() == null) {
            // Intake could not link the Patient (FHIR was down then); try again now.
            Optional<String> patientId;
            try {
                patientId = linker.lookup(pending.patientUserId(), messageId);
            } catch (FhirException e) {
                store.recordFailedAttempt(messageId, e.getMessage());
                return Outcome.RETRY_LATER;
            }
            if (patientId.isEmpty()) {
                store.markRejected(messageId, "PatientNotFound");
                return Outcome.REJECTED;
            }
            source = withPatient(source, patientId.get());
        }

        long started = System.nanoTime();
        try {
            FhirGateway.WriteResult result = gateway.createCommunication(CommunicationMapper.toCommunication(source));
            events.emit(messageId, FhirPatientLinker.SERVICE, "SUCCESS", elapsedMs(started));
            store.markWritten(messageId, source.fhirPatientId(), result.id());
            return Outcome.WRITTEN;
        } catch (FhirException e) {
            events.emit(messageId, FhirPatientLinker.SERVICE, "FAILED", elapsedMs(started));
            if (e.retryable()) {
                store.recordFailedAttempt(messageId, e.getMessage());
                log.warn("fhir write-back for {} will be retried: {}", messageId, e.getMessage());
                return Outcome.RETRY_LATER;
            }
            store.markRejected(messageId, e.getMessage());
            log.warn("fhir write-back for {} rejected: {}", messageId, e.getMessage());
            return Outcome.REJECTED;
        }
    }

    private static CommunicationMapper.Source withPatient(CommunicationMapper.Source s, String patientId) {
        return new CommunicationMapper.Source(s.messageId(), patientId, s.redactedText(), s.submittedAt(), s.publishedAt(),
                s.category(), s.routingConfidence(), s.routingModel(), s.urgencyLabel(), s.urgencyModel());
    }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    @PreDestroy
    void shutdown() throws InterruptedException {
        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);
    }
}
