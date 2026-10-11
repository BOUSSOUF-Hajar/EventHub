package com.eventhub.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
public class CorsConfig {

    /**
     * Expose en tant que source de configuration, et non en tant que filtre autonome :
     * c'est Spring Security qui l'applique (cf. SecurityConfig), en tete de sa chaine.
     *
     * Un filtre CORS independant passe apres la securite. Or une pre-requete OPTIONS ne
     * porte jamais de jeton : sur une route protegee elle etait rejetee en 401 avant
     * d'atteindre le filtre, et le navigateur bloquait alors l'appel reel.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of("http://localhost:5173"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
