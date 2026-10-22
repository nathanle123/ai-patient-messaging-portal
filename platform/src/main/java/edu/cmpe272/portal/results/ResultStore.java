package edu.cmpe272.portal.results;

import java.sql.Timestamp;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ResultStore {

    static final int SERVICE_COUNT = 4;

    private final JdbcTemplate jdbc;

    public ResultStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Idempotent: a redelivered record hits the (message_id, service_name) unique constraint and is a
     * no-op. Throws DataIntegrityViolationException if the message does not exist (retried, then DLQ).
     *
     * @return true if this call stored the result, false if it was a duplicate
     */
    @Transactional
    public boolean persist(ResultEnvelope r) {
        int inserted = jdbc.update("""
                INSERT INTO ai_result (message_id, service_name, result, model_version, processed_at)
                VALUES (?, ?, ?::jsonb, ?, ?)
                ON CONFLICT (message_id, service_name) DO NOTHING
                """, r.messageId(), r.service(), r.result().toString(), truncate(r.modelVersion()),
                Timestamp.from(r.processedAt().toInstant()));
        if (inserted == 0) {
            return false;
        }
        switch (r.service()) {
            case "urgency" -> {
                String label = r.result().path("label").asText();
                jdbc.update("UPDATE message SET urgency_label = ?, urgency_rank = ? WHERE id = ?",
                        label, ResultEnvelope.URGENCY_RANK.get(label), r.messageId());
            }
            case "routing" -> jdbc.update("UPDATE message SET category = ? WHERE id = ?",
                    r.result().path("category").asText(), r.messageId());
            default -> { }
        }
        jdbc.update("""
                UPDATE message SET status = 'PROCESSED'
                WHERE id = ? AND status IN ('REDACTED', 'PUBLISHED')
                  AND (SELECT count(*) FROM ai_result WHERE message_id = ?) = ?
                """, r.messageId(), r.messageId(), SERVICE_COUNT);
        return true;
    }

    private static String truncate(String s) {
        return s == null || s.length() <= 128 ? s : s.substring(0, 128);
    }
}
