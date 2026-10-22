package edu.cmpe272.portal.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import edu.cmpe272.portal.user.AppUser;
import edu.cmpe272.portal.user.Role;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class JwtServiceTest {

    private static final String SECRET = "test-secret-that-is-at-least-32-bytes-long!";
    private static final Instant NOW = Instant.parse("2026-10-20T12:00:00Z");

    private final JwtProperties props = new JwtProperties(SECRET, Duration.ofMinutes(60), "portal-platform");
    private final AppUser user = new AppUser("maria.lopez", "hash", Role.PATIENT, "Maria Lopez", "MRN-482913");

    private JwtService at(Instant instant) {
        return new JwtService(props, Clock.fixed(instant, ZoneOffset.UTC));
    }

    @Test
    void issuedTokenVerifiesToSamePrincipal() {
        JwtService jwt = at(NOW);
        JwtService.IssuedToken issued = jwt.issue(user);

        assertThat(issued.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(60)));
        assertThat(jwt.verify(issued.token()))
                .contains(new AuthenticatedUser(user.getId(), "maria.lopez", Role.PATIENT));
    }

    @Test
    void expiredTokenIsRejected() {
        String token = at(NOW).issue(user).token();

        assertThat(at(NOW.plus(Duration.ofMinutes(61))).verify(token)).isEmpty();
    }

    @Test
    void tamperedTokenIsRejected() {
        String token = at(NOW).issue(user).token();
        String[] parts = token.split("\\.");
        // Swap the payload for one claiming the ADMIN role, keeping the original signature.
        String forgedPayload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"sub\":\"" + user.getId() + "\",\"username\":\"maria.lopez\",\"role\":\"ADMIN\","
                        + "\"iss\":\"portal-platform\",\"exp\":" + (NOW.getEpochSecond() + 3600) + "}").getBytes());

        assertThat(at(NOW).verify(parts[0] + "." + forgedPayload + "." + parts[2])).isEmpty();
    }

    @Test
    void tokenSignedWithAnotherKeyIsRejected() {
        var other = new JwtService(new JwtProperties("another-secret-that-is-also-32-bytes-long",
                Duration.ofMinutes(60), "portal-platform"), Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(at(NOW).verify(other.issue(user).token())).isEmpty();
    }

    @Test
    void garbageIsRejected() {
        assertThat(at(NOW).verify("not-a-jwt")).isEmpty();
        assertThat(at(NOW).verify("")).isEmpty();
    }

    @Test
    void shortSecretFailsFast() {
        assertThatThrownBy(() -> new JwtService(new JwtProperties("too-short", Duration.ofMinutes(5), "x")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32 bytes");
    }
}
