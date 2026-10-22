package edu.cmpe272.portal.messaging;

import java.time.Duration;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Retries messages that were redacted but whose publish Kafka never acknowledged (status REDACTED),
 * so a broker outage delays messages instead of losing them. Republishing is safe: AI results are
 * idempotent on (message_id, service_name).
 */
@Component
public class OutboxRepublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxRepublisher.class);

    private final MessageRepository messages;
    private final IntakeService intake;
    private final Duration republishAfter;

    public OutboxRepublisher(MessageRepository messages, IntakeService intake,
                             @Value("${app.outbox.republish-after:10s}") Duration republishAfter) {
        this.messages = messages;
        this.intake = intake;
        this.republishAfter = republishAfter;
    }

    @Scheduled(fixedDelayString = "${app.outbox.interval:15s}", initialDelayString = "${app.outbox.interval:15s}")
    public void republishStale() {
        var stale = messages.findTop100ByStatusAndSubmittedAtBeforeOrderBySubmittedAt(
                MessageStatus.REDACTED, OffsetDateTime.now().minus(republishAfter));
        int published = 0;
        for (Message m : stale) {
            if (intake.publishAndMark(m)) {
                published++;
            } else {
                break; // Kafka still unavailable; try again next run
            }
        }
        if (!stale.isEmpty()) {
            log.info("outbox: republished {} of {} pending messages", published, stale.size());
        }
    }
}
