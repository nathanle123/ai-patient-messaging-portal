package edu.cmpe272.portal.auth;

import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param secret HMAC-SHA256 signing key; must be at least 32 bytes. Supplied via {@code JWT_SECRET}.
 * @param ttl    token lifetime
 * @param issuer value of the {@code iss} claim
 */
@Validated
@ConfigurationProperties("app.jwt")
public record JwtProperties(@NotBlank String secret, Duration ttl, @NotBlank String issuer) {

    public JwtProperties {
        if (ttl == null) {
            ttl = Duration.ofMinutes(60);
        }
    }
}
