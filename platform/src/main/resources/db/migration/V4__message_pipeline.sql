-- REDACTED: redaction succeeded and the redacted text is stored, but Kafka has not acknowledged the
-- publish yet. The outbox republisher retries these until they reach PUBLISHED.
ALTER TABLE message DROP CONSTRAINT message_status_check;
ALTER TABLE message ADD CONSTRAINT message_status_check
    CHECK (status IN ('RECEIVED', 'REDACTED', 'REDACTION_FAILED', 'PUBLISHED', 'PROCESSED'));

CREATE INDEX ix_message_status_submitted ON message (status, submitted_at);
