package edu.cmpe272.portal.audit;

import edu.cmpe272.portal.auth.AuthenticatedUser;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditService {

    private static final int MAX_USERNAME = 64;

    private final AuditLogRepository repository;

    public AuditService(AuditLogRepository repository) {
        this.repository = repository;
    }

    /** Written in its own transaction so the record survives a rollback of the caller's work. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(AuthenticatedUser actor, AuditAction action, String targetType, String targetId,
                       String detail) {
        repository.save(new AuditLogEntry(actor.id(), actor.username(), actor.role(), action,
                targetType, targetId, detail));
    }

    /** For events without an authenticated actor, such as a failed login. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordAnonymous(AuditAction action, String attemptedUsername) {
        String username = attemptedUsername == null ? null
                : attemptedUsername.substring(0, Math.min(attemptedUsername.length(), MAX_USERNAME));
        repository.save(new AuditLogEntry(null, username, null, action, null, null, null));
    }

    @Transactional(readOnly = true)
    public Page<AuditLogEntry> search(AuditAction action, Pageable pageable) {
        return action == null ? repository.findAll(pageable) : repository.findByAction(action, pageable);
    }
}
