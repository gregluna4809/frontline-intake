package com.frontline.auth;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.frontline.staff.StaffRepository;
import java.util.Base64;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

@Configuration
public class SecurityConfiguration {
    static final String ISSUER = "frontline";

    @Bean
    SecretKey jwtSigningKey(@Value("${auth.jwt-secret}") String secret) {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(secret);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("JWT_SECRET must be Base64-encoded", exception);
        }
        if (bytes.length < 32) {
            throw new IllegalArgumentException("JWT_SECRET must encode at least 32 random bytes");
        }
        return new SecretKeySpec(bytes, "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey jwtSigningKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(jwtSigningKey));
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey jwtSigningKey, StaffRepository staff) {
        var decoder = NimbusJwtDecoder.withSecretKey(jwtSigningKey).macAlgorithm(MacAlgorithm.HS256).build();
        OAuth2TokenValidator<Jwt> currentStaff = jwt -> {
            Object id = jwt.getClaim("staffId");
            boolean valid = id instanceof Number number && staff.findById(number.longValue())
                    .filter(person -> person.active() && person.role().name().equals(jwt.getClaimAsString("role")))
                    .isPresent();
            return valid ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(
                    new OAuth2Error("invalid_token", "Staff access has changed. Log in again.", null));
        };
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(ISSUER), currentStaff));
        return decoder;
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper mapper, com.frontline.sandbox.SandboxProperties sandbox) throws Exception {
        AccessDeniedHandler forbidden = (request, response, exception) -> {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.setContentType("application/problem+json");
            mapper.writeValue(response.getOutputStream(), ProblemDetail.forStatusAndDetail(
                    HttpStatus.FORBIDDEN, "Your role is not permitted to perform this action."));
        };
        var authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("role");
        authorities.setAuthorityPrefix("ROLE_");
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return http
                .addFilterBefore(new com.frontline.sandbox.SandboxModeFilter(sandbox), org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter.class)
                // Authentication uses an explicit bearer header, never a session cookie.
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .exceptionHandling(errors -> errors.authenticationEntryPoint(new BearerTokenAuthenticationEntryPoint())
                        .accessDeniedHandler(forbidden))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(HttpMethod.GET, "/api/sandbox/state", "/api/sandbox/summary").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/sandbox/login").permitAll()
                        .requestMatchers("/api/sandbox/**").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/visits", "/api/auth/login").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/business-status", "/api/snapshot", "/api/visit-reasons").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/visits/{id}/help-requests")
                            .hasAnyRole("CLERK", "LSR", "SUPERVISING_LSR", "CENTER_MANAGER")
                        .requestMatchers(HttpMethod.GET, "/api/help-requests")
                            .hasAnyRole("CLERK", "LSR", "SUPERVISING_LSR", "CENTER_MANAGER")
                        .requestMatchers(HttpMethod.POST, "/api/help-requests/{id}/claim", "/api/help-requests/{id}/close")
                            .hasAnyRole("LSR", "SUPERVISING_LSR", "CENTER_MANAGER")
                        .requestMatchers(HttpMethod.POST, "/api/visits/{id}/phone-override")
                            .hasAnyRole("SUPERVISING_LSR", "CENTER_MANAGER")
                        .requestMatchers("/api/admin/**").hasRole("ADMINISTRATOR")
                        .requestMatchers(HttpMethod.GET, "/api/simulator/switches").hasAnyRole("CENTER_MANAGER", "ADMINISTRATOR", "SUPERVISING_LSR")
                        .requestMatchers(HttpMethod.PUT, "/api/simulator/switches/{role}").hasAnyRole("CENTER_MANAGER", "ADMINISTRATOR")
                        .requestMatchers("/api/reports/**").hasAnyRole("CENTER_MANAGER", "ADMINISTRATOR")
                        .requestMatchers(HttpMethod.POST, "/api/customers").hasAnyRole("CLERK", "CENTER_MANAGER")
                        .requestMatchers(HttpMethod.POST, "/api/visits/{id}/identify", "/api/visits/{id}/route")
                            .hasAnyRole("CLERK", "CENTER_MANAGER")
                        .requestMatchers(HttpMethod.POST, "/api/visits/{id}/assign", "/api/visits/{id}/close",
                                "/api/visits/{id}/left")
                            .hasAnyRole("LSR", "CENTER_MANAGER")
                        .requestMatchers(HttpMethod.POST, "/api/visits/{id}/assign-staff", "/api/visits/{id}/report-ftr",
                                "/api/visits/{id}/reschedule", "/api/call-outs")
                            .hasAnyRole("SUPERVISING_LSR", "CENTER_MANAGER")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(resource -> resource.accessDeniedHandler(forbidden)
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(converter)))
                .build();
    }
}
