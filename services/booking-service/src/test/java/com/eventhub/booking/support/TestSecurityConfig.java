package com.eventhub.booking.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.time.Instant;
import java.util.Map;

/**
 * Fournir un JwtDecoder suffit a faire reculer l'auto-configuration OAuth2 de Spring Boot,
 * qui sinon tenterait de joindre Keycloak au demarrage du contexte de test.
 *
 * Les tests fabriquent leurs jetons via SecurityMockMvcRequestPostProcessors.jwt(), donc
 * ce decodeur n'est jamais reellement sollicite.
 */
@TestConfiguration
public class TestSecurityConfig {

    @Bean
    public JwtDecoder jwtDecoder() {
        return token -> new Jwt(token, Instant.now(), Instant.now().plusSeconds(300),
                Map.of("alg", "none"), Map.of("sub", "test"));
    }
}
