package com.frontline.auth;

import java.util.Base64;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SecurityConfigurationTest {
    private final SecurityConfiguration configuration = new SecurityConfiguration();

    @Test
    void signingKeyRejectsEmptyMalformedAndShortSecrets() {
        for (String secret : new String[] {"", "not-base64!", Base64.getEncoder().encodeToString(new byte[31])}) {
            assertThatThrownBy(() -> configuration.jwtSigningKey(secret)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void signingKeyUsesDecodedEnvironmentValue() {
        byte[] bytes = new byte[32];
        new java.security.SecureRandom().nextBytes(bytes);
        var key = configuration.jwtSigningKey(Base64.getEncoder().encodeToString(bytes));
        assertThat(key.getAlgorithm()).isEqualTo("HmacSHA256");
        assertThat(key.getEncoded()).containsExactly(bytes);
    }
}
