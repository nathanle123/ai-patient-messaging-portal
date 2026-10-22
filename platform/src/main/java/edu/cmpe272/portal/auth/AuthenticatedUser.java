package edu.cmpe272.portal.auth;

import edu.cmpe272.portal.user.Role;
import java.util.UUID;

/** The principal placed in the security context after a valid JWT is presented. */
public record AuthenticatedUser(UUID id, String username, Role role) {
}
