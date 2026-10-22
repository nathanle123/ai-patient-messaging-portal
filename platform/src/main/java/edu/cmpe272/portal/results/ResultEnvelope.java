package edu.cmpe272.portal.results;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** A validated record from results.&lt;service&gt;. */
public record ResultEnvelope(UUID messageId, String service, String modelVersion, OffsetDateTime processedAt,
                             JsonNode result) {

    static final Set<String> URGENCY_LABELS = Set.of("urgent", "soon", "routine");
    static final Set<String> CATEGORIES =
            Set.of("medication_refill", "scheduling", "test_results", "billing", "symptoms", "other");
    static final Map<String, Integer> URGENCY_RANK = Map.of("urgent", 0, "soon", 1, "routine", 2);

    static ResultEnvelope parse(String expectedService, String value, ObjectMapper json) {
        JsonNode root;
        try {
            root = json.readTree(value);
        } catch (Exception e) {
            throw new InvalidResultException("not JSON");
        }
        if (root == null || !root.isObject()) {
            throw new InvalidResultException("not a JSON object");
        }
        String service = root.path("service").asText(null);
        if (!expectedService.equals(service)) {
            throw new InvalidResultException("service mismatch");
        }
        UUID messageId;
        OffsetDateTime processedAt;
        try {
            messageId = UUID.fromString(root.path("message_id").asText(""));
            processedAt = OffsetDateTime.parse(root.path("processed_at").asText(""));
        } catch (IllegalArgumentException | DateTimeParseException e) {
            throw new InvalidResultException("bad message_id or processed_at");
        }
        JsonNode result = root.path("result");
        if (!result.isObject()) {
            throw new InvalidResultException("missing result object");
        }
        switch (service) {
            case "urgency" -> require(URGENCY_LABELS.contains(result.path("label").asText()), "bad urgency label");
            case "routing" -> require(CATEGORIES.contains(result.path("category").asText()), "bad category");
            case "summarization" -> require(result.path("summary").isTextual(), "missing summary");
            case "search" -> require(result.path("neighbors").isArray(), "missing neighbors");
            default -> throw new InvalidResultException("unknown service");
        }
        String model = root.path("model_version").asText(null);
        return new ResultEnvelope(messageId, service, model, processedAt, result);
    }

    private static void require(boolean ok, String reason) {
        if (!ok) {
            throw new InvalidResultException(reason);
        }
    }
}
