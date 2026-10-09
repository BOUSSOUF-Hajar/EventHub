package com.eventhub.event.messaging;

import com.eventhub.event.config.RabbitMQConfig;
import com.eventhub.event.domain.Event;
import com.eventhub.event.repository.EventRepository;
import com.eventhub.event.repository.ProcessedMessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

// EVT-5 sur un vrai broker : un "booking.confirmed" publie comme le fait booking-service
// doit se retrouver dans les places restantes du catalogue, une fois et une seule.
@SpringBootTest
@Testcontainers
class SeatAvailabilityIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    // RabbitMQ + management est lent a demarrer sur un poste charge : delai allonge.
    @Container
    static RabbitMQContainer rabbitmq = new RabbitMQContainer("rabbitmq:3.12-management-alpine")
            .withStartupTimeout(Duration.ofMinutes(5));

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.rabbitmq.host", rabbitmq::getHost);
        registry.add("spring.rabbitmq.port", rabbitmq::getAmqpPort);
        registry.add("spring.rabbitmq.username", rabbitmq::getAdminUsername);
        registry.add("spring.rabbitmq.password", rabbitmq::getAdminPassword);
    }

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private ProcessedMessageRepository processedMessageRepository;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private RabbitAdmin rabbitAdmin;

    // Evite l'appel a Keycloak au demarrage du contexte.
    @MockBean
    private JwtDecoder jwtDecoder;

    private Event event;

    @BeforeEach
    void setUp() {
        rabbitAdmin.purgeQueue(RabbitMQConfig.BOOKING_CONFIRMED_QUEUE, false);
        processedMessageRepository.deleteAll();
        eventRepository.deleteAll();
        event = eventRepository.save(new Event("Concert Jazz", "Soiree jazz", "Rabat",
                Instant.parse("2027-01-15T20:00:00Z"), 10, new BigDecimal("35.00")));
    }

    @Test
    @DisplayName("EVT-5 : booking.confirmed retire les places de la reservation des places restantes")
    void confirmedBookingDecrementsRemainingSeats() {
        publishConfirmed(UUID.randomUUID().toString(), event.getId(), 3);

        awaitRemainingSeats(event.getId(), 7);
        assertThat(eventRepository.findById(event.getId()).orElseThrow().getTotalCapacity())
                .as("la capacite totale ne bouge pas")
                .isEqualTo(10);
    }

    @Test
    @DisplayName("le meme evenement livre deux fois n'est decompte qu'une fois")
    void duplicateMessageIsAppliedOnce() {
        String messageId = UUID.randomUUID().toString();

        publishConfirmed(messageId, event.getId(), 3);
        publishConfirmed(messageId, event.getId(), 3);
        // Sentinelle : queue FIFO et listener mono-thread, donc une fois celle-ci
        // appliquee, les deux messages precedents l'ont ete aussi.
        publishConfirmed(UUID.randomUUID().toString(), event.getId(), 1);

        // 10 - 3 - 1 = 6. Un double decompte donnerait 3.
        awaitRemainingSeats(event.getId(), 6);
    }

    @Test
    @DisplayName("les places restantes ne deviennent jamais negatives")
    void remainingSeatsNeverGoNegative() {
        publishConfirmed(UUID.randomUUID().toString(), event.getId(), 8);
        publishConfirmed(UUID.randomUUID().toString(), event.getId(), 5); // il n'en reste que 2
        publishConfirmed(UUID.randomUUID().toString(), event.getId(), 1);

        awaitRemainingSeats(event.getId(), 1);
    }

    @Test
    @DisplayName("un message illisible ou visant un evenement supprime ne bloque pas les suivants")
    void poisonAndOrphanMessagesDoNotBlockTheQueue() {
        send("ceci n'est pas du JSON");
        send("{\"messageId\":\"m-1\",\"eventId\":\"%s\"}".formatted(event.getId())); // sans seatCount
        publishConfirmed(UUID.randomUUID().toString(), UUID.randomUUID(), 2);        // evenement inconnu

        publishConfirmed(UUID.randomUUID().toString(), event.getId(), 2);

        awaitRemainingSeats(event.getId(), 8);
        assertThat(rabbitAdmin.getQueueInfo(RabbitMQConfig.BOOKING_CONFIRMED_QUEUE).getMessageCount()).isZero();
    }

    /** Meme enveloppe que celle ecrite par l'OutboxWriter de booking-service. */
    private void publishConfirmed(String messageId, UUID eventId, int seatCount) {
        send("""
                {
                  "messageId": "%s",
                  "type": "booking.confirmed",
                  "occurredAt": "2026-10-07T20:37:39.104Z",
                  "bookingId": "%s",
                  "eventId": "%s",
                  "customerId": "%s",
                  "customerEmail": "alice@example.com",
                  "seatCount": %d,
                  "amount": 50.00
                }
                """.formatted(messageId, UUID.randomUUID(), eventId, UUID.randomUUID(), seatCount));
    }

    private void send(String body) {
        rabbitTemplate.send("booking.events", "booking.confirmed",
                MessageBuilder.withBody(body.getBytes(StandardCharsets.UTF_8))
                        .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                        .setMessageId(UUID.randomUUID().toString())
                        .build());
    }

    private void awaitRemainingSeats(UUID eventId, int expected) {
        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(eventRepository.findById(eventId).orElseThrow().getRemainingSeats())
                        .isEqualTo(expected));
    }
}
