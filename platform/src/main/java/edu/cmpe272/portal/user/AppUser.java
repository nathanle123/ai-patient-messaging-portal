package edu.cmpe272.portal.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "app_user")
public class AppUser {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true, length = 64)
    private String username;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Role role;

    @Column(name = "display_name", nullable = false, length = 128)
    private String displayName;

    /** Medical record number; links a patient account to its FHIR Patient. Null for staff. */
    @Column(length = 32)
    private String mrn;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected AppUser() {
    }

    public AppUser(String username, String passwordHash, Role role, String displayName, String mrn) {
        this.id = UUID.randomUUID();
        this.username = username;
        this.passwordHash = passwordHash;
        this.role = role;
        this.displayName = displayName;
        this.mrn = mrn;
        this.createdAt = OffsetDateTime.now();
    }

    public UUID getId() { return id; }
    public String getUsername() { return username; }
    public String getPasswordHash() { return passwordHash; }
    public Role getRole() { return role; }
    public String getDisplayName() { return displayName; }
    public String getMrn() { return mrn; }
    public boolean isEnabled() { return enabled; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
