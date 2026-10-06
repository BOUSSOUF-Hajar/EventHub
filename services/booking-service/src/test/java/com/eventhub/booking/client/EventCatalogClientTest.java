package com.eventhub.booking.client;

import com.eventhub.booking.web.error.EventCatalogUnavailableException;
import com.eventhub.booking.web.error.EventNotFoundException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Le client est mocke partout ailleurs : ce test est le seul a verifier la traduction
 * des reponses HTTP d'event-service. Il parle a un vrai serveur HTTP local plutot qu'a
 * un double de RestClient, pour exercer aussi la deserialisation et les delais.
 */
class EventCatalogClientTest {

    private static final UUID EVENT_ID = UUID.randomUUID();

    private HttpServer server;
    private int status = 200;
    private String body = "";

    private EventCatalogClient client;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/events/" + EVENT_ID, exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        server.start();

        client = new EventCatalogClient(RestClient.builder(), new EventServiceProperties(
                "http://localhost:" + server.getAddress().getPort(),
                Duration.ofSeconds(1), Duration.ofSeconds(1)));
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    @DisplayName("200 : capacite, prix et date sont lus, les autres champs ignores")
    void readsTheFieldsBookingNeeds() {
        body = """
                {"id":"%s","title":"Concert","venue":"Rabat","startsAt":"2027-01-15T20:00:00Z",
                 "totalCapacity":200,"remainingSeats":180,"unitPrice":35.00}
                """.formatted(EVENT_ID);

        EventSummary summary = client.findById(EVENT_ID);

        assertThat(summary.totalCapacity()).isEqualTo(200);
        assertThat(summary.unitPrice()).isEqualByComparingTo("35.00");
        assertThat(summary.startsAt()).isEqualTo(Instant.parse("2027-01-15T20:00:00Z"));
    }

    @Test
    @DisplayName("404 : l'evenement est introuvable")
    void notFoundMeansUnknownEvent() {
        status = 404;

        assertThatThrownBy(() -> client.findById(EVENT_ID))
                .isInstanceOf(EventNotFoundException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 429, 500, 503})
    @DisplayName("toute autre erreur est une panne du catalogue, pas un evenement introuvable")
    void otherErrorsAreADependencyFailure(int errorStatus) {
        status = errorStatus;

        assertThatThrownBy(() -> client.findById(EVENT_ID))
                .isInstanceOf(EventCatalogUnavailableException.class);
    }

    @Test
    @DisplayName("un prix absent n'est pas un prix nul : la reponse est refusee")
    void missingPriceIsRejected() {
        body = """
                {"id":"%s","startsAt":"2027-01-15T20:00:00Z","totalCapacity":200}
                """.formatted(EVENT_ID);

        assertThatThrownBy(() -> client.findById(EVENT_ID))
                .isInstanceOf(EventCatalogUnavailableException.class);
    }

    @Test
    @DisplayName("une date absente est refusee")
    void missingStartDateIsRejected() {
        body = """
                {"id":"%s","totalCapacity":200,"unitPrice":35.00}
                """.formatted(EVENT_ID);

        assertThatThrownBy(() -> client.findById(EVENT_ID))
                .isInstanceOf(EventCatalogUnavailableException.class);
    }

    @Test
    @DisplayName("event-service injoignable : panne du catalogue")
    void unreachableServiceIsADependencyFailure() {
        server.stop(0);

        assertThatThrownBy(() -> client.findById(EVENT_ID))
                .isInstanceOf(EventCatalogUnavailableException.class);
    }
}
