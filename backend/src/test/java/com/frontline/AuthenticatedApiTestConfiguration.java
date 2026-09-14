package com.frontline;

import java.time.Instant;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcBuilderCustomizer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** General workflow regressions run as the manager, who can perform every visit action. */
@TestConfiguration
public class AuthenticatedApiTestConfiguration {
    @Bean
    MockMvcBuilderCustomizer authenticatedRequests(JwtEncoder encoder) {
        var now = Instant.now();
        var claims = JwtClaimsSet.builder().issuer("frontline").subject("staff9")
                .issuedAt(now).expiresAt(now.plusSeconds(28800)).claim("staffId", 9)
                .claim("role", "CENTER_MANAGER").claim("displayName", "Demo Center Manager 001").build();
        String token = encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        return builder -> builder.defaultRequest(get("/").header("Authorization", "Bearer " + token));
    }
}
