package edu.cmpe272.portal.auth;

import edu.cmpe272.portal.audit.AuditAction;
import edu.cmpe272.portal.audit.AuditService;
import edu.cmpe272.portal.auth.AuthDtos.LoginResponse;
import edu.cmpe272.portal.auth.AuthDtos.UserView;
import edu.cmpe272.portal.user.AppUser;
import edu.cmpe272.portal.user.AppUserRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuditService audit;
    /** Compared against when the username is unknown, so response time doesn't reveal which usernames exist. */
    private final String dummyHash;

    public AuthService(AppUserRepository users, PasswordEncoder passwordEncoder, JwtService jwtService,
                       AuditService audit) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.audit = audit;
        this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    public LoginResponse login(String username, String password) {
        Optional<AppUser> found = users.findByUsername(username).filter(AppUser::isEnabled);
        boolean matches = passwordEncoder.matches(password, found.map(AppUser::getPasswordHash).orElse(dummyHash));
        if (found.isEmpty() || !matches) {
            audit.recordAnonymous(AuditAction.LOGIN_FAILURE, username);
            throw new InvalidCredentialsException();
        }
        AppUser user = found.get();
        audit.record(new AuthenticatedUser(user.getId(), user.getUsername(), user.getRole()),
                AuditAction.LOGIN_SUCCESS, "app_user", user.getId().toString(), null);
        JwtService.IssuedToken token = jwtService.issue(user);
        return new LoginResponse(token.token(), token.expiresAt(), UserView.of(user));
    }

    public Optional<UserView> currentUser(AuthenticatedUser principal) {
        return users.findById(principal.id()).map(UserView::of);
    }
}
