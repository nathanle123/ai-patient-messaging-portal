-- Message pipeline tables. The application code that uses them arrives in Phase 3, but the schema is
-- fixed now so the PHI boundary (message vs message_original) is designed up front.

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE message (
    id                    UUID PRIMARY KEY,
    patient_id            UUID         NOT NULL REFERENCES app_user (id),
    status                VARCHAR(24)  NOT NULL
        CHECK (status IN ('RECEIVED', 'REDACTION_FAILED', 'PUBLISHED', 'PROCESSED')),
    -- Output of the PHI service. NULL until redaction succeeds; the only text that leaves the platform.
    redacted_text         TEXT,
    phi_entity_count      INTEGER,
    -- Denormalised from ai_result so the clinician inbox sorts with one indexed query.
    category              VARCHAR(32)
        CHECK (category IN ('medication_refill', 'scheduling', 'test_results', 'billing', 'symptoms', 'other')),
    urgency_label         VARCHAR(16)  CHECK (urgency_label IN ('urgent', 'soon', 'routine')),
    -- 0 = urgent, 1 = soon, 2 = routine, 9 = not yet classified.
    urgency_rank          SMALLINT     NOT NULL DEFAULT 9 CHECK (urgency_rank IN (0, 1, 2, 9)),
    submitted_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at          TIMESTAMPTZ,
    reviewed_at           TIMESTAMPTZ,
    reviewed_by           UUID         REFERENCES app_user (id),
    fhir_patient_id       VARCHAR(64),
    fhir_communication_id VARCHAR(64),
    CONSTRAINT reviewed_pair CHECK ((reviewed_at IS NULL) = (reviewed_by IS NULL))
);

-- Clinician inbox: unreviewed first, then urgent -> soon -> routine -> unclassified, then oldest first.
CREATE INDEX ix_message_inbox ON message ((reviewed_at IS NOT NULL), urgency_rank, submitted_at);
CREATE INDEX ix_message_patient ON message (patient_id, submitted_at DESC);
CREATE INDEX ix_message_submitted_at ON message (submitted_at);

-- Restricted: the unredacted text. Readable via the API only by clinicians and the submitting
-- patient, and every read is audited. AI-service database roles get no grant on this table.
CREATE TABLE message_original (
    message_id    UUID PRIMARY KEY REFERENCES message (id) ON DELETE CASCADE,
    original_text TEXT        NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One row per (message, service); the unique constraint makes Kafka consumers idempotent.
CREATE TABLE ai_result (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    message_id    UUID        NOT NULL REFERENCES message (id) ON DELETE CASCADE,
    service_name  VARCHAR(32) NOT NULL
        CHECK (service_name IN ('urgency', 'routing', 'summarization', 'search')),
    result        JSONB       NOT NULL,
    model_version VARCHAR(128),
    processed_at  TIMESTAMPTZ NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_ai_result_message_service UNIQUE (message_id, service_name)
);

-- Fixed schema from service.events; no free-text columns.
CREATE TABLE service_event (
    event_id    UUID PRIMARY KEY,
    message_id  UUID,
    service     VARCHAR(32) NOT NULL,
    status      VARCHAR(16) NOT NULL,
    latency_ms  INTEGER     NOT NULL CHECK (latency_ms >= 0),
    occurred_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX ix_service_event_time ON service_event (occurred_at);
CREATE INDEX ix_service_event_service_time ON service_event (service, occurred_at);

-- Vector store owned by the similarity-search service (all-MiniLM-L6-v2, 384 dimensions).
-- Embeddings are computed from redacted text only.
CREATE TABLE message_embedding (
    message_id    UUID PRIMARY KEY REFERENCES message (id) ON DELETE CASCADE,
    embedding     vector(384)  NOT NULL,
    model_version VARCHAR(128) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX ix_message_embedding_hnsw ON message_embedding USING hnsw (embedding vector_cosine_ops);
