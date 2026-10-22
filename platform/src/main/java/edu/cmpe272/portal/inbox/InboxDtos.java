package edu.cmpe272.portal.inbox;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class InboxDtos {

    private InboxDtos() {
    }

    public record InboxItem(UUID id, String patientName, OffsetDateTime submittedAt, String source, String status,
                            String urgencyLabel, String category, String summary, String preview,
                            int resultCount, OffsetDateTime reviewedAt) {
    }

    public record InboxCounts(long unreviewed, long unreviewedUrgent, long reviewed, long redactionFailed) {
    }

    public record InboxPage(List<InboxItem> items, InboxCounts counts, int page, int size, long totalItems) {
    }

    public record SimilarMessage(UUID id, double score, OffsetDateTime submittedAt, String category,
                                 String urgencyLabel, String summary) {
    }

    public record MessageDetail(UUID id, UUID patientId, String patientName, OffsetDateTime submittedAt, String source,
                                OffsetDateTime publishedAt, String status, String redactedText, Integer phiEntityCount,
                                String urgencyLabel, String category, OffsetDateTime reviewedAt, String reviewedBy,
                                Map<String, JsonNode> results, List<SimilarMessage> similar,
                                double similarityThreshold, FhirLink fhir) {
    }

    /** FHIR R4 linkage: the Patient found on intake and the Communication written back (status null = pending). */
    public record FhirLink(String patientId, String communicationId, String status) {
    }

    public record OriginalText(UUID id, String originalText) {
    }
}
