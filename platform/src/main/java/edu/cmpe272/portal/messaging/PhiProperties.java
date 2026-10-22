package edu.cmpe272.portal.messaging;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.phi")
public record PhiProperties(String url, Duration connectTimeout, Duration readTimeout) {
}
