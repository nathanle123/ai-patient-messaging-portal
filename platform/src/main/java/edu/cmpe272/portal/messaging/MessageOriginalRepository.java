package edu.cmpe272.portal.messaging;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MessageOriginalRepository extends JpaRepository<MessageOriginal, UUID> {

    List<MessageOriginal> findByMessageIdIn(Collection<UUID> messageIds);
}
