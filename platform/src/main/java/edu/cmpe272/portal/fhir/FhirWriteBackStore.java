package edu.cmpe272.portal.fhir;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Write-back state lives on {@code message}: {@code fhir_status} is NULL while pending, then
 * WRITTEN or REJECTED. A message is ready once both its routing and urgency results are stored.
 */
@Repository
public class FhirWriteBackStore {

    /** A message that is ready to write: both results present, redacted text present, not yet written. */
    private static final String READY = """
            FROM message m
            JOIN ai_result r ON r.message_id = m.id AND r.service_name = 'routing'
            JOIN ai_result u ON u.message_id = m.id AND u.service_name = 'urgency'
            WHERE m.fhir_status IS NULL AND m.redacted_text IS NOT NULL
            """;

    public record Pending(UUID messageId, UUID patientUserId, CommunicationMapper.Source source) {
    }

    public record Counts(long written, long pending, long rejected, OffsetDateTime lastWrittenAt) {
    }

    private final JdbcTemplate jdbc;

    public FhirWriteBackStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Pending> findReady(UUID messageId) {
        return jdbc.query("""
                SELECT m.id, m.patient_id, m.fhir_patient_id, m.redacted_text, m.submitted_at, m.published_at,
                       m.category, m.urgency_label, (r.result ->> 'confidence')::float8 AS confidence,
                       r.model_version AS routing_model, u.model_version AS urgency_model
                """ + READY + " AND m.id = ?", this::pending, messageId).stream().findFirst();
    }

    /**
     * Oldest first, so a backlog drains in submission order; messages that already failed go to the
     * back, so one that keeps failing cannot hold up the rest.
     */
    public List<UUID> readyIds(int limit) {
        return jdbc.queryForList("SELECT m.id " + READY + " ORDER BY m.fhir_attempts, m.submitted_at LIMIT ?",
                UUID.class, limit);
    }

    /** @return false if another writer got there first (the conditional create returned the same resource) */
    public boolean markWritten(UUID messageId, String patientId, String communicationId) {
        return jdbc.update("""
                UPDATE message SET fhir_status = 'WRITTEN', fhir_patient_id = ?, fhir_communication_id = ?,
                       fhir_written_at = now(), fhir_attempts = fhir_attempts + 1, fhir_error = NULL
                WHERE id = ? AND fhir_status IS NULL
                """, patientId, communicationId, messageId) == 1;
    }

    public void markRejected(UUID messageId, String error) {
        jdbc.update("""
                UPDATE message SET fhir_status = 'REJECTED', fhir_attempts = fhir_attempts + 1, fhir_error = ?
                WHERE id = ? AND fhir_status IS NULL
                """, truncate(error), messageId);
    }

    public void recordFailedAttempt(UUID messageId, String error) {
        jdbc.update("UPDATE message SET fhir_attempts = fhir_attempts + 1, fhir_error = ? WHERE id = ? AND fhir_status IS NULL",
                truncate(error), messageId);
    }

    /** Make every message pending again (e.g. after the in-memory mock restarted). */
    public int resetAll() {
        return jdbc.update("""
                UPDATE message SET fhir_status = NULL, fhir_communication_id = NULL, fhir_written_at = NULL,
                       fhir_attempts = 0, fhir_error = NULL
                WHERE fhir_status IS NOT NULL OR fhir_attempts > 0
                """);
    }

    public Counts counts() {
        return jdbc.queryForObject("""
                SELECT count(*) FILTER (WHERE m.fhir_status = 'WRITTEN'),
                       count(*) FILTER (WHERE m.fhir_status IS NULL AND m.category IS NOT NULL AND m.urgency_label IS NOT NULL),
                       count(*) FILTER (WHERE m.fhir_status = 'REJECTED'),
                       max(m.fhir_written_at)
                FROM message m
                """, (rs, i) -> new Counts(rs.getLong(1), rs.getLong(2), rs.getLong(3),
                rs.getObject(4, OffsetDateTime.class)));
    }

    private Pending pending(ResultSet rs, int row) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        Number confidence = (Number) rs.getObject("confidence");
        return new Pending(id, rs.getObject("patient_id", UUID.class), new CommunicationMapper.Source(id,
                rs.getString("fhir_patient_id"), rs.getString("redacted_text"),
                rs.getObject("submitted_at", OffsetDateTime.class), rs.getObject("published_at", OffsetDateTime.class),
                rs.getString("category"), confidence == null ? null : confidence.doubleValue(), rs.getString("routing_model"),
                rs.getString("urgency_label"), rs.getString("urgency_model")));
    }

    private static String truncate(String s) {
        return s == null || s.length() <= 64 ? s : s.substring(0, 64);
    }
}
