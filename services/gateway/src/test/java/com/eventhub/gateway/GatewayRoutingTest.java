package com.eventhub.gateway;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

// Routage et resilience du Gateway face a de faux services : un serveur HTTP local joue
// event-service et booking-service, et payment-service pointe sur un port ferme pour
// simuler un service arrete. Pas de Testcontainers ici : le Gateway n'a ni base ni broker.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayRoutingTest {

    private static final String TOKEN = "customer-token";

    private static final DisposableServer backend = HttpServer.create()
            .port(0)
            .route(routes -> routes
                    .get("/api/events", (request, response) -> response
                            .header(HttpHeaders.CONTENT_TYPE, "application/json")
                            .sendString(Mono.just("[{\"title\":\"depuis event-service\"}]")))
                    // Renvoie l'en-tete recu, pour verifier que le jeton est relaye au service.
                    .get("/api/bookings/me", (request, response) -> response
                            .sendString(Mono.just(String.valueOf(
                                    request.requestHeaders().get(HttpHeaders.AUTHORIZATION))))))
            .bindNow();

    @DynamicPropertySource
    static void serviceUrls(DynamicPropertyRegistry registry) throws IOException {
        String backendUrl = "http://localhost:" + backend.port();
        registry.add("eventhub.services.event-service-url", () -> backendUrl);
        registry.add("eventhub.services.booking-service-url", () -> backendUrl);
        int closedPort = closedPort();
        registry.add("eventhub.services.payment-service-url", () -> "http://localhost:" + closedPort);
    }

    @AfterAll
    static void stopBackend() {
        backend.disposeNow();
    }

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    // Evite l'appel a Keycloak : tout jeton TOKEN est accepte.
    @MockBean
    private ReactiveJwtDecoder jwtDecoder;

    @BeforeEach
    void setUp() {
        Jwt jwt = Jwt.withTokenValue(TOKEN)
                .header("alg", "none")
                .subject("customer-1")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
        when(jwtDecoder.decode(TOKEN)).thenReturn(Mono.just(jwt));
        circuitBreakerRegistry.circuitBreaker("payment-service").reset();
    }

    @Test
    void eventsAreRoutedToEventService() {
        webTestClient.get().uri("/api/events")
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$[0].title").isEqualTo("depuis event-service");
    }

    @Test
    void bookingsAreRoutedWithTheCallerToken() {
        webTestClient.get().uri("/api/bookings/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo("Bearer " + TOKEN);
    }

    @Test
    void unavailableServiceGetsFallbackResponse() {
        webTestClient.get().uri("/api/payments/any")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectBody()
                .jsonPath("$.status").isEqualTo(503)
                .jsonPath("$.service").isEqualTo("payment-service")
                .jsonPath("$.detail").isNotEmpty();
    }

    // Le repli ne doit pas servir de contournement : sans jeton, la securite repond avant.
    @Test
    void unavailableServiceStillRequiresJwt() {
        webTestClient.get().uri("/api/payments/any")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void circuitOpensAfterRepeatedFailuresWithoutAffectingOtherServices() {
        for (int i = 0; i < 5; i++) {
            webTestClient.get().uri("/api/payments/any")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                    .exchange()
                    .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        }

        assertThat(circuitBreakerRegistry.circuitBreaker("payment-service").getState())
                .isEqualTo(CircuitBreaker.State.OPEN);

        webTestClient.get().uri("/api/events")
                .exchange()
                .expectStatus().isOk();
    }

    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
