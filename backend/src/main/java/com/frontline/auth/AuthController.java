package com.frontline.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private static final Duration TOKEN_LIFETIME = Duration.ofHours(8);
    private final UserAccountRepository accounts;
    private final PasswordEncoder passwords;
    private final JwtEncoder tokens;
    private final String dummyHash;

    public AuthController(UserAccountRepository accounts, PasswordEncoder passwords, JwtEncoder tokens) {
        this.accounts = accounts;
        this.passwords = passwords;
        this.tokens = tokens;
        this.dummyHash = passwords.encode(UUID.randomUUID().toString());
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        var account = accounts.findByUsername(request.username());
        // Unknown users still incur the BCrypt check and receive the same failure response.
        boolean matches = passwords.matches(request.password(),
                account.map(UserAccountRepository.Account::passwordHash).orElse(dummyHash));
        if (account.isEmpty() || !matches) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid username or password.");
        }
        var user = account.orElseThrow();
        Instant issuedAt = Instant.now();
        var claims = JwtClaimsSet.builder().issuer(SecurityConfiguration.ISSUER)
                .subject(user.username()).issuedAt(issuedAt).expiresAt(issuedAt.plus(TOKEN_LIFETIME))
                .claim("staffId", user.staff().id())
                .claim("role", user.staff().role().name())
                .claim("displayName", user.staff().displayName()).build();
        String token = tokens.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        return new LoginResponse(token, "Bearer", TOKEN_LIFETIME.toSeconds(),
                user.staff().id(), user.staff().role().name(), user.staff().displayName());
    }

    public record LoginRequest(@NotBlank @Size(max = 100) String username,
                               @NotBlank @Size(max = 72) String password) { }
    public record LoginResponse(String token, String tokenType, long expiresIn,
                                long staffId, String role, String displayName) { }
}
