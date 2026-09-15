package com.frontline;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "business.scheduling.enabled=false")
@AutoConfigureMockMvc
@Testcontainers
@Import(AuthApiTest.IdentityController.class)
class AuthApiTest {
    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired JwtDecoder decoder;
    @Autowired JwtEncoder encoder;
    @Autowired PasswordEncoder passwords;

    private String login(String username, String password) throws Exception {
        var result = mvc.perform(post("/api/auth/login").contentType("application/json")
                        .content(mapper.writeValueAsString(Map.of("username", username, "password", password))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(28800)).andReturn();
        assertThat(result.getRequest().getSession(false)).isNull();
        assertThat(result.getResponse().getHeader("Set-Cookie")).isNull();
        var body = mapper.readTree(result.getResponse().getContentAsString());
        var jwt = decoder.decode(body.path("token").asText());
        assertThat(body.path("staffId").asLong()).isEqualTo(((Number) jwt.getClaim("staffId")).longValue());
        assertThat(body.path("role").asText()).isEqualTo(jwt.getClaimAsString("role"));
        assertThat(body.path("displayName").asText()).isEqualTo(jwt.getClaimAsString("displayName"));
        return body.path("token").asText();
    }

    @Test
    void everySeededAccountLogsInWithCorrectClaimsAndCanAccessProtectedEndpoints() throws Exception {
        var users = jdbc.queryForList("SELECT u.username, s.id, s.role, s.display_name FROM user_account u JOIN staff s ON s.id = u.staff_id");
        assertThat(users).hasSize(10);
        for (var user : users) {
            String token = login((String) user.get("username"), "demo1234");
            Jwt jwt = decoder.decode(token);
            assertThat(jwt.getSubject()).isEqualTo(user.get("username"));
            assertThat(((Number) jwt.getClaim("staffId")).longValue()).isEqualTo(user.get("id"));
            assertThat(jwt.getClaimAsString("role")).isEqualTo(user.get("role"));
            assertThat(jwt.getClaimAsString("displayName")).isEqualTo(user.get("display_name"));
            assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofHours(8));
            mvc.perform(get("/api/staff").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
            mvc.perform(post("/api/simulator/stop").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
            mvc.perform(get("/api/test-identity").header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.role").value(user.get("role")))
                    .andExpect(jsonPath("$.displayName").value(user.get("display_name")))
                    .andExpect(jsonPath("$.staffId").value(((Number) user.get("id")).intValue()))
                    .andExpect(jsonPath("$.authority").value("ROLE_" + user.get("role")));
        }
        // A previous successful request does not establish a cookie session.
        mvc.perform(get("/api/staff")).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @CsvSource({"missing,demo1234", "staff1,wrong", "missing,wrong"})
    void invalidCredentialsReturn401(String username, String password) throws Exception {
        mvc.perform(post("/api/auth/login").contentType("application/json")
                        .content(mapper.writeValueAsString(Map.of("username", username, "password", password))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Invalid username or password."));
    }

    @Test
    void onlyExactPublicMethodsAndPathsAllowAnonymousAccess() throws Exception {
        mvc.perform(get("/api/business-status")).andExpect(status().isOk());
        mvc.perform(get("/api/snapshot").param("date", "2026-09-11")).andExpect(status().isOk());
        mvc.perform(post("/api/visits").contentType("application/json")
                        .content("{\"visitType\":\"WALK_IN\",\"reason\":\"PHONE_ACCESS\"}"))
                .andExpect(status().isCreated());
        for (String path : List.of("/api/staff", "/api/customers", "/api/resources", "/api/rotation",
                "/api/call-outs", "/api/visits", "/api/visits/1", "/api/simulator/status",
                "/api/auth/login", "/api/unknown", "/error")) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
        for (String path : List.of("/api/business-status", "/api/snapshot", "/api/visits/1/close", "/api/simulator/start")) {
            mvc.perform(post(path)).andExpect(status().isUnauthorized());
        }
        mvc.perform(get("/api/staff").header("Authorization", "Basic c3RhZmYxOmRlbW8xMjM0"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void malformedExpiredTamperedAndWrongIssuerTokensReturn401() throws Exception {
        String valid = login("staff1", "demo1234");
        String[] parts = valid.split("\\.");
        String tampered = parts[0] + "." + parts[1] + "." + (parts[2].startsWith("A") ? "B" : "A") + parts[2].substring(1);
        for (String token : List.of("not-a-jwt", tampered,
                signedToken("frontline", Instant.now().minusSeconds(3600)),
                signedToken("wrong-issuer", Instant.now().plusSeconds(3600)))) {
            mvc.perform(get("/api/staff").header("Authorization", "Bearer " + token))
                    .andExpect(status().isUnauthorized());
        }
    }

    private String signedToken(String issuer, Instant expiry) {
        var claims = JwtClaimsSet.builder().issuer(issuer).subject("staff1")
                .issuedAt(expiry.minusSeconds(28800)).expiresAt(expiry).claim("staffId", 1)
                .claim("role", "LSR").claim("displayName", "Demo Staff 001").build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    @Test
    void migrationSeedsAllRolesAndOneBcryptAccountPerStaff() {
        assertThat(jdbc.queryForMap("""
                SELECT count(*) FILTER (WHERE role = 'LSR') AS lsr,
                    count(*) FILTER (WHERE role = 'SUPERVISING_LSR') AS supervisor,
                    count(*) FILTER (WHERE role = 'CLERK') AS clerk,
                    count(*) FILTER (WHERE role = 'CENTER_MANAGER') AS manager,
                    count(*) FILTER (WHERE role = 'ADMINISTRATOR') AS administrator FROM staff
                """)).containsExactlyInAnyOrderEntriesOf(Map.of("lsr", 4L, "supervisor", 1L, "clerk", 3L, "manager", 1L, "administrator", 1L));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM staff s WHERE (SELECT count(*) FROM user_account u WHERE u.staff_id = s.id) <> 1", Integer.class)).isZero();
        for (String hash : jdbc.queryForList("SELECT password_hash FROM user_account", String.class)) {
            assertThat(hash).startsWith("$2a$10$");
            assertThat(passwords.matches("demo1234", hash)).isTrue();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_account WHERE created_at IS NULL", Integer.class)).isZero();
        assertThatThrownBy(() -> jdbc.update("INSERT INTO user_account (staff_id, username, password_hash) SELECT staff_id, 'duplicate', password_hash FROM user_account LIMIT 1"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO user_account (staff_id, username, password_hash) SELECT 99999, 'orphan', password_hash FROM user_account LIMIT 1"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void upgradeAlsoCreatesAccountForPreexistingCustomStaffAndRunsOnlyOnce() {
        String schema = "auth_upgrade";
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .schemas(schema).defaultSchema(schema).target("10").load().migrate();
        jdbc.update("INSERT INTO auth_upgrade.staff (display_name, role) VALUES ('Demo Extra Staff', 'LSR')");
        var upgrade = Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .schemas(schema).defaultSchema(schema).load();
        upgrade.migrate();
        assertThat(upgrade.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_upgrade.staff", Integer.class)).isEqualTo(11);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_upgrade.user_account", Integer.class)).isEqualTo(11);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_upgrade.staff s WHERE (SELECT count(*) FROM auth_upgrade.user_account u WHERE u.staff_id = s.id) <> 1", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT username FROM auth_upgrade.user_account u JOIN auth_upgrade.staff s ON s.id = u.staff_id WHERE s.display_name = 'Demo Extra Staff'", String.class))
                .isEqualTo("staff6");
    }

    @RestController
    static class IdentityController {
        @GetMapping("/api/test-identity")
        Map<String, Object> identity(Authentication authentication) {
            Jwt jwt = (Jwt) authentication.getPrincipal();
            return Map.of("staffId", jwt.getClaim("staffId"), "role", jwt.getClaimAsString("role"),
                    "displayName", jwt.getClaimAsString("displayName"),
                    "authority", authentication.getAuthorities().iterator().next().getAuthority());
        }
    }
}
