-- FHIR R4 write-back state (Phase 5). fhir_patient_id and fhir_communication_id exist since V2.
--   fhir_status NULL      pending (written once both routing and urgency results are stored)
--               WRITTEN   Communication created on the FHIR server (fhir_communication_id)
--               REJECTED  the server refused it (4xx) or no Patient matches the account's MRN
-- fhir_error holds an exception class and HTTP status only, never a response body.
ALTER TABLE message
    ADD COLUMN fhir_status     VARCHAR(16) CHECK (fhir_status IN ('WRITTEN', 'REJECTED')),
    ADD COLUMN fhir_attempts   SMALLINT    NOT NULL DEFAULT 0,
    ADD COLUMN fhir_error      VARCHAR(64),
    ADD COLUMN fhir_written_at TIMESTAMPTZ;

CREATE INDEX ix_message_fhir_pending ON message (fhir_attempts, submitted_at) WHERE fhir_status IS NULL;
