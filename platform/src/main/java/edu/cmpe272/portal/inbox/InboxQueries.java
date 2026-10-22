package edu.cmpe272.portal.inbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cmpe272.portal.inbox.InboxDtos.FhirLink;
import edu.cmpe272.portal.inbox.InboxDtos.InboxCounts;
import edu.cmpe272.portal.inbox.InboxDtos.InboxItem;
import edu.cmpe272.portal.inbox.InboxDtos.MessageDetail;
import edu.cmpe272.portal.inbox.InboxDtos.SimilarMessage;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Read model for the clinician inbox. Never selects message_original. */
@Repository
public class InboxQueries {

    public enum View { UNREVIEWED, REVIEWED, ALL }

    private static final int PREVIEW_CHARS = 180;

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public InboxQueries(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /**
     * Unreviewed first; within that urgent → soon → routine → not yet classified; then oldest first.
     * Matches index ix_message_inbox.
     */
    public List<InboxItem> list(View view, int page, int size) {
        return jdbc.query("""
                SELECT m.id, u.display_name, m.submitted_at, m.source, m.status, m.urgency_label, m.category,
                       s.result ->> 'summary' AS summary, left(m.redacted_text, ?) AS preview,
                       (SELECT count(*) FROM ai_result r WHERE r.message_id = m.id) AS result_count,
                       m.reviewed_at
                FROM message m
                JOIN app_user u ON u.id = m.patient_id
                LEFT JOIN ai_result s ON s.message_id = m.id AND s.service_name = 'summarization'
                WHERE m.status <> 'RECEIVED' AND %s
                ORDER BY (m.reviewed_at IS NOT NULL), m.urgency_rank, m.submitted_at
                LIMIT ? OFFSET ?
                """.formatted(filter(view)), (rs, i) -> new InboxItem(
                rs.getObject("id", UUID.class), rs.getString("display_name"), time(rs, "submitted_at"),
                rs.getString("source"), rs.getString("status"), rs.getString("urgency_label"), rs.getString("category"),
                rs.getString("summary"), rs.getString("preview"), rs.getInt("result_count"),
                time(rs, "reviewed_at")), PREVIEW_CHARS, size, (long) page * size);
    }

    public long count(View view) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM message m WHERE m.status <> 'RECEIVED' AND " + filter(view),
                Long.class);
        return n == null ? 0 : n;
    }

    public InboxCounts counts() {
        return jdbc.queryForObject("""
                SELECT count(*) FILTER (WHERE reviewed_at IS NULL) AS unreviewed,
                       count(*) FILTER (WHERE reviewed_at IS NULL AND urgency_label = 'urgent') AS unreviewed_urgent,
                       count(*) FILTER (WHERE reviewed_at IS NOT NULL) AS reviewed,
                       count(*) FILTER (WHERE status = 'REDACTION_FAILED' AND reviewed_at IS NULL) AS failed
                FROM message WHERE status <> 'RECEIVED'
                """, (rs, i) -> new InboxCounts(rs.getLong("unreviewed"), rs.getLong("unreviewed_urgent"),
                rs.getLong("reviewed"), rs.getLong("failed")));
    }

    public Optional<MessageDetail> detail(UUID id, double threshold) {
        Optional<MessageDetail> base = jdbc.query("""
                SELECT m.*, u.display_name, r.display_name AS reviewer
                FROM message m
                JOIN app_user u ON u.id = m.patient_id
                LEFT JOIN app_user r ON r.id = m.reviewed_by
                WHERE m.id = ? AND m.status <> 'RECEIVED'
                """, (rs, i) -> new MessageDetail(id, rs.getObject("patient_id", UUID.class),
                rs.getString("display_name"), time(rs, "submitted_at"), rs.getString("source"), time(rs, "published_at"),
                rs.getString("status"), rs.getString("redacted_text"), (Integer) rs.getObject("phi_entity_count"),
                rs.getString("urgency_label"), rs.getString("category"), time(rs, "reviewed_at"),
                rs.getString("reviewer"), Map.of(), List.of(), threshold, new FhirLink(rs.getString("fhir_patient_id"),
                rs.getString("fhir_communication_id"), rs.getString("fhir_status"))), id).stream().findFirst();
        return base.map(d -> {
            Map<String, JsonNode> results = results(id);
            return new MessageDetail(d.id(), d.patientId(), d.patientName(), d.submittedAt(), d.source(), d.publishedAt(),
                    d.status(), d.redactedText(), d.phiEntityCount(), d.urgencyLabel(), d.category(), d.reviewedAt(),
                    d.reviewedBy(), results, similar(results.get("search"), threshold), threshold, d.fhir());
        });
    }

    private Map<String, JsonNode> results(UUID id) {
        Map<String, JsonNode> out = new LinkedHashMap<>();
        jdbc.query("SELECT service_name, result::text AS result FROM ai_result WHERE message_id = ? ORDER BY service_name",
                rs -> {
                    try {
                        out.put(rs.getString("service_name"), json.readTree(rs.getString("result")));
                    } catch (Exception e) {
                        throw new SQLException("unreadable result json");
                    }
                }, id);
        return out;
    }

    /** Neighbours from the search result, filtered by the similarity threshold, with redacted context. */
    private List<SimilarMessage> similar(JsonNode search, double threshold) {
        List<SimilarMessage> out = new ArrayList<>();
        if (search == null) {
            return out;
        }
        for (JsonNode n : search.path("neighbors")) {
            double score = n.path("score").asDouble();
            if (score < threshold) {
                continue;
            }
            UUID nid = UUID.fromString(n.path("message_id").asText());
            jdbc.query("""
                    SELECT m.submitted_at, m.category, m.urgency_label, s.result ->> 'summary' AS summary
                    FROM message m
                    LEFT JOIN ai_result s ON s.message_id = m.id AND s.service_name = 'summarization'
                    WHERE m.id = ?
                    """, rs -> {
                out.add(new SimilarMessage(nid, score, time(rs, "submitted_at"), rs.getString("category"),
                        rs.getString("urgency_label"), rs.getString("summary")));
            }, nid);
        }
        return out;
    }

    public int markReviewed(UUID id, UUID clinicianId) {
        return jdbc.update("""
                UPDATE message SET reviewed_at = now(), reviewed_by = ?
                WHERE id = ? AND reviewed_at IS NULL AND status <> 'RECEIVED'
                """, clinicianId, id);
    }

    public boolean exists(UUID id) {
        Boolean b = jdbc.queryForObject("SELECT exists(SELECT 1 FROM message WHERE id = ? AND status <> 'RECEIVED')",
                Boolean.class, id);
        return Boolean.TRUE.equals(b);
    }

    private static String filter(View view) {
        return switch (view) {
            case UNREVIEWED -> "m.reviewed_at IS NULL";
            case REVIEWED -> "m.reviewed_at IS NOT NULL";
            case ALL -> "TRUE";
        };
    }

    private static OffsetDateTime time(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class);
    }
}
