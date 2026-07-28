package com.eventhub.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

// TODO: une fois Keycloak demarre, decommenter la conversion des roles realm Keycloak
// en authorities Spring Security (claim "realm_access.roles") via un JwtAuthenticationConverter.
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    private static final String[] PUBLIC_PATHS = {
            "/api/events/**",      // catalogue public, lecture libre
            "/actuator/health"
    };

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        return http
                .csrf(csrf -> csrf.disable())
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers(PUBLIC_PATHS).permitAll()
                        .anyExchange().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> {}))
                .build();
    }
}
