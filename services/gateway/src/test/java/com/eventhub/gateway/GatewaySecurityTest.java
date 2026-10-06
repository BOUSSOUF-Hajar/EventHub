package com.eventhub.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

// Verifie les regles de securite du Gateway, sans Keycloak ni service aval demarres :
// on ne teste que ce que le Gateway decide lui-meme (public / JWT obligatoire / CORS).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewaySecurityTest {

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void healthIsPublic() {
        webTestClient.get().uri("/actuator/health")
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("UP");
    }

    @Test
    void bookingsRequireJwt() {
        webTestClient.get().uri("/api/bookings/me")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void paymentsRequireJwt() {
        webTestClient.get().uri("/api/payments/any")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    // Le service aval n'est pas demarre : la route echoue, mais pas pour une raison de securite.
    @Test
    void eventsCatalogueIsNotBlockedBySecurity() {
        webTestClient.get().uri("/api/events")
                .exchange()
                .expectStatus().value(status -> assertThat(status)
                        .isNotIn(HttpStatus.UNAUTHORIZED.value(), HttpStatus.FORBIDDEN.value()));
    }

    @Test
    void corsPreflightAllowsFrontendOrigin() {
        webTestClient.options().uri("/api/events")
                .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173");
    }

    @Test
    void corsPreflightRejectsUnknownOrigin() {
        webTestClient.options().uri("/api/events")
                .header(HttpHeaders.ORIGIN, "http://evil.example")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .exchange()
                .expectStatus().isForbidden();
    }
}
