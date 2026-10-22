-- Accounts and the audit trail.

CREATE TABLE app_user (
    id            UUID PRIMARY KEY,
    username      VARCHAR(64)  NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    role          VARCHAR(16)  NOT NULL CHECK (role IN ('PATIENT', 'CLINICIAN', 'ADMIN')),
    display_name  VARCHAR(128) NOT NULL,
    -- Links a patient account to its FHIR Patient (Patient.identifier). NULL for staff.
    mrn           VARCHAR(32)  UNIQUE,
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT patient_has_mrn CHECK (role <> 'PATIENT' OR mrn IS NOT NULL)
);

-- Append-only. Never holds message text; detail is a short fixed-vocabulary note.
CREATE TABLE audit_log (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    occurred_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    actor_user_id  UUID         REFERENCES app_user (id),
    actor_username VARCHAR(64),
    actor_role     VARCHAR(16),
    action         VARCHAR(48)  NOT NULL,
    target_type    VARCHAR(48),
    target_id      VARCHAR(64),
    detail         VARCHAR(255)
);

CREATE INDEX ix_audit_log_occurred_at ON audit_log (occurred_at DESC);
CREATE INDEX ix_audit_log_action ON audit_log (action, occurred_at DESC);
CREATE INDEX ix_audit_log_actor ON audit_log (actor_user_id, occurred_at DESC);
