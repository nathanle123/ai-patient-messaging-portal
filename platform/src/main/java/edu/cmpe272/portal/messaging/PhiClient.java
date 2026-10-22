package edu.cmpe272.portal.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Synchronous call to the PHI detection service. Any failure or timeout is a {@link RedactionException}.
 *
 * <p>A connection-level failure (reset, refused, stale keep-alive socket after the PHI container
 * restarts) is retried once: redaction has no side effects. Timeouts are not retried, so a slow PHI
 * service costs at most one read timeout.
 */
@Component
public class PhiClient {

    static final int MAX_ATTEMPTS = 2;

    private final RestClient client;

    public PhiClient(PhiProperties properties, RestClient.Builder builder) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.connectTimeout());
        factory.setReadTimeout(properties.readTimeout());
        this.client = builder.baseUrl(properties.url()).requestFactory(factory).build();
    }

    public PhiResult redact(UUID messageId, String text) {
        PhiResult result = null;
        for (int attempt = 1; ; attempt++) {
            try {
                result = client.post()
                        .uri("/process")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("message_id", messageId.toString(), "text", text))
                        .retrieve()
                        .body(PhiResult.class);
                break;
            } catch (ResourceAccessException e) {
                if (isTimeout(e)) {
                    throw new RedactionException(RedactionException.Reason.TIMEOUT, e);
                }
                if (attempt >= MAX_ATTEMPTS) {
                    throw new RedactionException(RedactionException.Reason.ERROR, e);
                }
            } catch (RestClientException e) {
                // A read timeout while extracting the body surfaces here rather than as ResourceAccessException.
                throw new RedactionException(isTimeout(e) ? RedactionException.Reason.TIMEOUT : RedactionException.Reason.ERROR, e);
            }
        }
        if (result == null || result.redactedText() == null || !messageId.equals(result.messageId())) {
            throw new RedactionException(RedactionException.Reason.INVALID_RESPONSE, null);
        }
        return result;
    }

    private static boolean isTimeout(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.net.SocketTimeoutException) {
                return true;
            }
        }
        return false;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PhiResult(
            @JsonProperty("message_id") UUID messageId,
            @JsonProperty("redacted_text") String redactedText,
            @JsonProperty("phi_entity_count") int phiEntityCount,
            @JsonProperty("entity_counts") Map<String, Integer> entityCounts) {

        @Override
        public String toString() {
            return "PhiResult[" + messageId + ", entities=" + phiEntityCount + "]";
        }
    }
}
