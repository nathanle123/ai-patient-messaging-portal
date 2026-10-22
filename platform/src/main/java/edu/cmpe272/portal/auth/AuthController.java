package edu.cmpe272.portal.auth;

import edu.cmpe272.portal.auth.AuthDtos.LoginRequest;
import edu.cmpe272.portal.auth.AuthDtos.LoginResponse;
import edu.cmpe272.portal.auth.AuthDtos.UserView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "Authentication")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    @SecurityRequirements
    @Operation(summary = "Exchange username and password for a JWT (HS256, 60 min)")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request.username(), request.password());
    }

    @GetMapping("/me")
    @Operation(summary = "Return the authenticated user")
    public ResponseEntity<UserView> me(@AuthenticationPrincipal AuthenticatedUser principal) {
        return ResponseEntity.of(authService.currentUser(principal));
    }
}
