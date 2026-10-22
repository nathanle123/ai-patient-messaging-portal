package edu.cmpe272.portal.messaging;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface MessageRepository extends JpaRepository<Message, UUID> {

    List<Message> findTop50ByPatientIdOrderBySubmittedAtDesc(UUID patientId);

    List<Message> findTop100ByStatusAndSubmittedAtBeforeOrderBySubmittedAt(MessageStatus status, OffsetDateTime before);

    /** Only moves REDACTED forward, so a late duplicate ack can't regress a PROCESSED message. */
    @Modifying
    @Query("update Message m set m.status = edu.cmpe272.portal.messaging.MessageStatus.PUBLISHED, "
            + "m.publishedAt = :at where m.id = :id and m.status = edu.cmpe272.portal.messaging.MessageStatus.REDACTED")
    int markPublished(UUID id, OffsetDateTime at);
}
