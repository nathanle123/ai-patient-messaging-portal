package edu.cmpe272.portal.audit;

import edu.cmpe272.portal.user.Role;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Append-only audit record. Must never carry message text: {@code detail} is a short, fixed-vocabulary
 * note such as a reason code, not free text from a request.
 */
@Entity
@Table(name = "audit_log")
public class AuditLogEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "occurred_at", nullable = false)
    private OffsetDateTime occurredAt;

    @Column(name = "actor_user_id")
    private UUID actorUserId;

    @Column(name = "actor_username", length = 64)
    private String actorUsername;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_role", length = 16)
    private Role actorRole;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 48)
    private AuditAction action;

    @Column(name = "target_type", length = 48)
    private String targetType;

    @Column(name = "target_id", length = 64)
    private String targetId;

    @Column(length = 255)
    private String detail;

    protected AuditLogEntry() {
    }

    AuditLogEntry(UUID actorUserId, String actorUsername, Role actorRole, AuditAction action,
                  String targetType, String targetId, String detail) {
        this.occurredAt = OffsetDateTime.now();
        this.actorUserId = actorUserId;
        this.actorUsername = actorUsername;
        this.actorRole = actorRole;
        this.action = action;
        this.targetType = targetType;
        this.targetId = targetId;
        this.detail = detail;
    }

    public Long getId() { return id; }
    public OffsetDateTime getOccurredAt() { return occurredAt; }
    public UUID getActorUserId() { return actorUserId; }
    public String getActorUsername() { return actorUsername; }
    public Role getActorRole() { return actorRole; }
    public AuditAction getAction() { return action; }
    public String getTargetType() { return targetType; }
    public String getTargetId() { return targetId; }
    public String getDetail() { return detail; }
}
