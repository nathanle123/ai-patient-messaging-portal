-- Provenance of every message, so analytics can include or exclude seeded data:
--   manual     submitted through the patient portal UI or the normal API
--   portalpal  text from the PortalPal-AI Hugging Face datasets (seed script)
--   synthetic  labelled template messages covering non-symptom categories (seed script)
ALTER TABLE message ADD COLUMN source VARCHAR(16) NOT NULL DEFAULT 'manual'
    CHECK (source IN ('manual', 'portalpal', 'synthetic'));
CREATE INDEX ix_message_source_submitted ON message (source, submitted_at);
CREATE INDEX ix_service_event_message ON service_event (message_id);

-- Historical analytics, written only by the scheduled aggregation job (AnalyticsAggregator).
-- Counts only: no message text, no patient identifiers. NULL category/urgency is 'unclassified'.
CREATE TABLE message_stats_hourly (
    bucket_start   TIMESTAMPTZ NOT NULL,
    source         VARCHAR(16) NOT NULL,
    category       VARCHAR(32) NOT NULL,
    urgency_label  VARCHAR(16) NOT NULL,
    message_count  INTEGER     NOT NULL,
    reviewed_count INTEGER     NOT NULL,
    computed_at    TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (bucket_start, source, category, urgency_label)
);

CREATE TABLE message_stats_daily (
    bucket_date    DATE        NOT NULL,
    source         VARCHAR(16) NOT NULL,
    category       VARCHAR(32) NOT NULL,
    urgency_label  VARCHAR(16) NOT NULL,
    message_count  INTEGER     NOT NULL,
    reviewed_count INTEGER     NOT NULL,
    computed_at    TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (bucket_date, source, category, urgency_label)
);

CREATE TABLE service_stats_hourly (
    bucket_start   TIMESTAMPTZ NOT NULL,
    service        VARCHAR(32) NOT NULL,
    event_count    INTEGER     NOT NULL,
    failure_count  INTEGER     NOT NULL,
    p50_latency_ms INTEGER,
    p95_latency_ms INTEGER,
    computed_at    TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (bucket_start, service)
);

CREATE TABLE analytics_job_run (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    job          VARCHAR(48) NOT NULL,
    trigger      VARCHAR(16) NOT NULL,
    started_at   TIMESTAMPTZ NOT NULL,
    finished_at  TIMESTAMPTZ NOT NULL,
    rows_written INTEGER     NOT NULL
);
CREATE INDEX ix_analytics_job_run_job ON analytics_job_run (job, finished_at DESC);
