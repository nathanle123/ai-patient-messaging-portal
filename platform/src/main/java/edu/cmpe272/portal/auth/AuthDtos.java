package edu.cmpe272.portal.auth;

import edu.cmpe272.portal.user.AppUser;
import edu.cmpe272.portal.user.Role;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

public final class AuthDtos {

    private AuthDtos() {
    }

    public record LoginRequest(
            @NotBlank @Size(max = 64) String username,
            @NotBlank @Size(max = 128) String password) {
    }

    public record LoginResponse(String token, Instant expiresAt, UserView user) {
    }

    public record UserView(UUID id, String username, Role role, String displayName) {

        public static UserView of(AppUser user) {
            return new UserView(user.getId(), user.getUsername(), user.getRole(), user.getDisplayName());
        }
    }
}
