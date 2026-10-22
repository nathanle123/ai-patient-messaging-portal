package edu.cmpe272.portal.auth;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param enabled      create demo accounts at startup
 * @param demoPassword shared password for demo accounts ({@code DEMO_USER_PASSWORD})
 * @param fakePhiPath  synthetic identities CSV; one patient account per row
 */
@ConfigurationProperties("app.seed")
public record SeedProperties(boolean enabled, String demoPassword, Path fakePhiPath) {
}
