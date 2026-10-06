package com.eventhub.booking.outbox;

import com.eventhub.booking.client.EventCatalogClient;
import com.eventhub.booking.client.EventSummary;
import com.eventhub.booking.domain.Booking;
import com.eventhub.booking.domain.BookingStatus;
import com.eventhub.booking.domain.OutboxEvent;
import com.eventhub.booking.repository.BookingRepository;
import com.eventhub.booking.repository.OutboxEventRepository;
import com.eventhub.booking.service.AuthenticatedCustomer;
import com.eventhub.booking.service.BookingService;
import com.eventhub.booking.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * Definition of Done du lot 2 : l'evenement "booking.requested" doit reellement
 * atterrir sur l'exchange RabbitMQ, pas seulement dans la table d'outbox.
 */
class OutboxRelayIntegrationTest extends AbstractIntegrationTest {

    private static final String TEST_QUEUE = "test.booking-requested.queue";

    @Autowired
    private BookingService bookingService;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private OutboxRelay outboxRelay;

    @Autowired
    private RabbitAdmin rabbitAdmin;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private TopicExchange bookingExchange;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private EventCatalogClient eventCatalogClient;

    private final AuthenticatedCustomer alice =
            new AuthenticatedCustomer(UUID.randomUUID(), "alice@example.com");

    @BeforeEach
    void prepareBroker() {
        outboxEventRepository.deleteAll();
        bookingRepository.deleteAll();

        // Ni durable ni auto-delete : RabbitTemplate.receive(queue, timeout) ouvre un
        // consommateur puis le ferme, ce qui suffirait a faire disparaitre une queue
        // auto-delete entre deux appels. L'isolation entre tests vient du purge ci-dessous.
        Queue queue = new Queue(TEST_QUEUE, false, false, false);
        rabbitAdmin.declareQueue(queue);
        rabbitAdmin.declareBinding(BindingBuilder.bind(queue).to(bookingExchange).with("booking.requested"));
        rabbitAdmin.purgeQueue(TEST_QUEUE, false);

        given(eventCatalogClient.findById(any(UUID.class)))
                .willReturn(new EventSummary(UUID.randomUUID(), 50, new BigDecimal("25.00"),
                        Instant.now().plus(Duration.ofDays(30))));
    }

    @Test
    @DisplayName("le relais publie l'evenement, le marque publie et fait passer la reservation en AWAITING_PAYMENT")
    void relayPublishesPendingEventsAndAdvancesTheSaga() throws Exception {
        Booking booking = bookingService.create(UUID.randomUUID(), 2, alice);

        outboxRelay.publishPending();

        Message message = rabbitTemplate.receive(TEST_QUEUE, 5_000);
        assertThat(message).as("message recu sur l'exchange booking.events").isNotNull();

        MessageProperties properties = message.getMessageProperties();
        assertThat(properties.getContentType()).isEqualTo(MessageProperties.CONTENT_TYPE_JSON);
        assertThat(properties.getMessageId()).as("cle de deduplication cote consommateur").isNotBlank();

        JsonNode payload = objectMapper.readTree(new String(message.getBody(), StandardCharsets.UTF_8));
        assertThat(payload.get("type").asText()).isEqualTo("booking.requested");
        assertThat(payload.get("messageId").asText()).isEqualTo(properties.getMessageId());
        assertThat(payload.get("bookingId").asText()).isEqualTo(booking.getId().toString());
        assertThat(payload.get("seatCount").asInt()).isEqualTo(2);

        assertThat(outboxEventRepository.findByAggregateIdOrderByCreatedAtAsc(booking.getId()))
                .singleElement()
                .satisfies(event -> assertThat(event.getPublishedAt()).isNotNull());

        assertThat(bookingRepository.findById(booking.getId()))
                .get()
                .extracting(Booking::getStatus)
                .isEqualTo(BookingStatus.AWAITING_PAYMENT);
    }

    @Test
    @DisplayName("un evenement qu'aucune queue n'accepte reste dans l'outbox au lieu d'etre perdu")
    void unroutableEventIsKeptForRetry() {
        UUID aggregateId = UUID.randomUUID();
        OutboxEvent orphan = outboxEventRepository.save(new OutboxEvent(
                UUID.randomUUID(), "booking", aggregateId, "booking.routing-key-sans-binding", "{}"));

        outboxRelay.publishPending();

        // RabbitMQ ACQUITTE ce message puis le jette faute de binding : sans le flag
        // mandatory, on le marquerait publie et il disparaitrait definitivement.
        assertThat(outboxEventRepository.findById(orphan.getId()))
                .get()
                .extracting(OutboxEvent::getPublishedAt)
                .as("conserve pour republication")
                .isNull();
    }

    @Test
    @DisplayName("dans un lot mixte, seul l'evenement routable est marque publie")
    void routableAndUnroutableEventsAreHandledIndependently() {
        Booking booking = bookingService.create(UUID.randomUUID(), 1, alice);
        OutboxEvent orphan = outboxEventRepository.save(new OutboxEvent(
                UUID.randomUUID(), "booking", UUID.randomUUID(), "booking.routing-key-sans-binding", "{}"));

        outboxRelay.publishPending();

        assertThat(rabbitTemplate.receive(TEST_QUEUE, 5_000)).isNotNull();
        assertThat(outboxEventRepository.findByAggregateIdOrderByCreatedAtAsc(booking.getId()))
                .singleElement()
                .satisfies(event -> assertThat(event.getPublishedAt()).isNotNull());
        assertThat(outboxEventRepository.findById(orphan.getId()))
                .get()
                .extracting(OutboxEvent::getPublishedAt)
                .isNull();
    }

    @Test
    @DisplayName("un evenement deja publie n'est pas renvoye au passage suivant")
    void alreadyPublishedEventsAreNotSentTwice() {
        bookingService.create(UUID.randomUUID(), 1, alice);

        outboxRelay.publishPending();
        assertThat(rabbitTemplate.receive(TEST_QUEUE, 5_000)).isNotNull();

        outboxRelay.publishPending();
        assertThat(rabbitTemplate.receive(TEST_QUEUE, 1_000))
                .as("aucun doublon apres un passage a vide")
                .isNull();
    }
}
