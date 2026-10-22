package edu.cmpe272.portal.analytics;

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param liveWindow     how far back the in-memory live metrics look
 * @param healthInterval how often each service's /health is polled
 * @param services       service name to base URL (for health checks)
 */
@ConfigurationProperties("app.analytics")
public record AnalyticsProperties(String hourlyCron, String dailyCron, Duration liveWindow, Duration healthInterval,
                                  Map<String, String> services) {
}
