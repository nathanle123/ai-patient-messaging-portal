package edu.cmpe272.portal.messaging;

import java.util.Map;

public final class Topics {

    public static final String MESSAGES_REDACTED = "messages.redacted";
    public static final String SERVICE_EVENTS = "service.events";
    public static final String DLQ_SUFFIX = ".dlq";

    /** Result topic → ai_result.service_name. */
    public static final Map<String, String> RESULT_TOPICS = Map.of(
            "results.urgency", "urgency",
            "results.routing", "routing",
            "results.summarization", "summarization",
            "results.search", "search");

    private Topics() {
    }
}
