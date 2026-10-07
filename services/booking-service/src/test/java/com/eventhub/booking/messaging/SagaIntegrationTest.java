package com.eventhub.booking.messaging;

import com.eventhub.booking.client.EventCatalogClient;
import com.eventhub.booking.client.EventSummary;
import com.eventhub.booking.config.RabbitMQConfig;
import com.eventhub.booking.domain.Booking;
import com.eventhub.booking.domain.BookingStatus;
import com.eventhub.booking.domain.OutboxEvent;
import com.eventhub.booking.outbox.OutboxRelay;
import com.eventhub.booking.repository.BookingRepository;
import com.eventhub.booking.repository.OutboxEventRepository;
import com.eventhub.booking.service.AuthenticatedCustomer;
import com.eventhub.booking.service.BookingSaga;
import com.eventhub.booking.service.BookingService;
import com.eventhub.booking.service.SeatLockService;
import com.eventhub.booking.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * Definition of Done du lot 3 : les deux issues de la Saga, a travers un vrai broker.
 *
 * Le test tient le role de payment-service : il lit la demande que booking-service a
 * reellement publiee, puis repond sur l'exchange "payment.events" avec le message exact
 * que payment-service emet (son propre test d'integration, PaymentFlowIntegrationTest,
 * verifie de l'autre cote qu'il produit bien ce message a partir de cette demande).
 */
@TestPropertySource(properties = "spring.rabbitmq.listener.simple.auto-startup=true")
class SagaIntegrationTest extends AbstractIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final int CAPACITY = 10;

    @Autowired
    private BookingService bookingService;

    @Autowired
    private BookingSaga bookingSaga;

    @Autowired
    private SeatLockService seatLockService;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private OutboxRelay outboxRelay;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private RabbitAdmin rabbitAdmin;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private EventCatalogClient eventCatalogClient;

    private final AuthenticatedCustomer alice =
            new AuthenticatedCustomer(UUID.randomUUID(), "alice@example.com");

    private UUID eventId;

    @BeforeEach
    void reset() {
        outboxEventRepository.deleteAll();
        bookingRepository.deleteAll();
        rabbitAdmin.purgeQueue(RabbitMQConfig.PAYMENT_BOOKING_REQUESTED_QUEUE, false);
        rabbitAdmin.purgeQueue(RabbitMQConfig.BOOKING_PAYMENT_RESULT_QUEUE, false);
        rabbitAdmin.purgeQueue(RabbitMQConfig.NOTIFICATION_BOOKING_CONFIRMED_QUEUE, false);
        rabbitAdmin.purgeQueue(RabbitMQConfig.NOTIFICATION_BOOKING_CANCELLED_QUEUE, false);

        eventId = UUID.randomUUID();
        given(eventCatalogClient.findById(any(UUID.class)))
                .willReturn(new EventSummary(eventId, CAPACITY, new BigDecimal("25.00"),
                        Instant.now().plus(Duration.ofDays(30))));
    }

    @Test
    @DisplayName("reservation -> paiement reussi -> CONFIRMED, places conservees, booking.confirmed publie")
    void successfulPaymentConfirmsTheBooking() throws Exception {
        Booking booking = bookingService.create(eventId, 2, alice);
        outboxRelay.publishPending();
        JsonNode request = receivePaymentRequest();
        assertThat(request.get("bookingId").asText()).isEqualTo(booking.getId().toString());
        assertThat(statusOf(booking)).isEqualTo(BookingStatus.AWAITING_PAYMENT);

        publishPaymentResult("payment.succeeded", booking.getId(), null);

        awaitStatus(booking, BookingStatus.CONFIRMED);
        assertThat(seatLockService.reservedSeats(eventId)).as("places vendues, toujours comptees").isEqualTo(2);

        outboxRelay.publishPending();
        Message confirmed = rabbitTemplate.receive(RabbitMQConfig.NOTIFICATION_BOOKING_CONFIRMED_QUEUE, 5_000);
        assertThat(confirmed).as("booking.confirmed publie pour notification-service").isNotNull();
        JsonNode payload = objectMapper.readTree(confirmed.getBody());
        assertThat(payload.get("type").asText()).isEqualTo("booking.confirmed");
        assertThat(payload.get("bookingId").asText()).isEqualTo(booking.getId().toString());
        assertThat(payload.get("customerEmail").asText()).isEqualTo("alice@example.com");
        assertThat(payload.get("amount").decimalValue()).isEqualByComparingTo("50.00");
    }

    @Test
    @DisplayName("reservation -> paiement echoue -> CANCELLED, places restituees, booking.cancelled publie")
    void failedPaymentCancelsTheBookingAndReleasesTheSeats() throws Exception {
        Booking booking = bookingService.create(eventId, 2, alice);
        assertThat(seatLockService.reservedSeats(eventId)).isEqualTo(2);
        outboxRelay.publishPending();
        receivePaymentRequest();

        publishPaymentResult("payment.failed", booking.getId(), "CARD_DECLINED");

        awaitStatus(booking, BookingStatus.CANCELLED);
        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(seatLockService.reservedSeats(eventId)).as("places restituees").isZero());

        outboxRelay.publishPending();
        Message cancelled = rabbitTemplate.receive(RabbitMQConfig.NOTIFICATION_BOOKING_CANCELLED_QUEUE, 5_000);
        assertThat(cancelled).as("booking.cancelled publie pour notification-service").isNotNull();
        JsonNode payload = objectMapper.readTree(cancelled.getBody());
        assertThat(payload.get("type").asText()).isEqualTo("booking.cancelled");
        assertThat(payload.get("bookingId").asText()).isEqualTo(booking.getId().toString());
        assertThat(payload.get("reason").asText()).isEqualTo("PAYMENT_FAILED");
    }

    @Test
    @DisplayName("les places rendues par la compensation sont de nouveau reservables")
    void releasedSeatsCanBeBookedAgain() {
        Booking soldOut = bookingService.create(eventId, CAPACITY, alice);

        publishPaymentResult("payment.failed", soldOut.getId(), "CARD_DECLINED");
        awaitStatus(soldOut, BookingStatus.CANCELLED);

        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(seatLockService.reservedSeats(eventId)).isZero());
        assertThat(bookingService.create(eventId, CAPACITY, alice).getStatus()).isEqualTo(BookingStatus.PENDING);
    }

    @Test
    @DisplayName("un payment.failed livre deux fois ne rend les places qu'une fois")
    void duplicateFailureReleasesSeatsOnlyOnce() {
        Booking failed = bookingService.create(eventId, 2, alice);
        Booking other = bookingService.create(eventId, 3, alice);

        publishPaymentResult("payment.failed", failed.getId(), "CARD_DECLINED");
        publishPaymentResult("payment.failed", failed.getId(), "CARD_DECLINED");
        // Sentinelle : queue FIFO et listener mono-thread, donc une fois celle-ci
        // appliquee, les deux messages precedents l'ont ete aussi.
        publishPaymentResult("payment.succeeded", other.getId(), null);
        awaitStatus(other, BookingStatus.CONFIRMED);

        assertThat(statusOf(failed)).isEqualTo(BookingStatus.CANCELLED);
        // 5 prises - 2 rendues = 3. Un double release donnerait 1 : deux places fantomes.
        assertThat(seatLockService.reservedSeats(eventId)).isEqualTo(3);
        assertThat(outboxEventRepository.findByAggregateIdOrderByCreatedAtAsc(failed.getId()))
                .extracting(OutboxEvent::getEventType)
                .containsExactly("booking.requested", "booking.cancelled");
    }

    @Test
    @DisplayName("un payment.failed en retard n'annule pas une reservation confirmee")
    void lateFailureIsIgnoredOnceConfirmed() {
        Booking booking = bookingService.create(eventId, 2, alice);
        Booking sentinel = bookingService.create(eventId, 1, alice);

        publishPaymentResult("payment.succeeded", booking.getId(), null);
        publishPaymentResult("payment.failed", booking.getId(), "CARD_DECLINED");
        publishPaymentResult("payment.succeeded", sentinel.getId(), null);
        awaitStatus(sentinel, BookingStatus.CONFIRMED);

        assertThat(statusOf(booking)).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(seatLockService.reservedSeats(eventId)).isEqualTo(3);
    }

    @Test
    @DisplayName("un message illisible ou orphelin ne bloque pas les resultats suivants")
    void poisonMessagesDoNotBlockTheQueue() {
        Booking booking = bookingService.create(eventId, 1, alice);

        send("payment.succeeded", "ceci n'est pas du JSON");
        send("payment.succeeded", "{\"type\":\"payment.refunded\",\"bookingId\":\"%s\"}".formatted(booking.getId()));
        publishPaymentResult("payment.succeeded", UUID.randomUUID(), null); // reservation inconnue
        publishPaymentResult("payment.succeeded", booking.getId(), null);

        awaitStatus(booking, BookingStatus.CONFIRMED);
        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(rabbitAdmin.getQueueInfo(RabbitMQConfig.BOOKING_PAYMENT_RESULT_QUEUE).getMessageCount())
                        .as("aucun message ne tourne en boucle")
                        .isZero());
    }

    @Test
    @DisplayName("BKG-3 : une reservation impayee expire, ses places sont rendues et l'annulation publiee")
    void unpaidBookingExpires() throws Exception {
        Booking unpaid = bookingService.create(eventId, 2, alice);
        Booking paid = bookingService.create(eventId, 3, alice);
        publishPaymentResult("payment.succeeded", paid.getId(), null);
        awaitStatus(paid, BookingStatus.CONFIRMED);
        outboxRelay.publishPending();

        // seuil dans le futur : toute reservation encore impayee est consideree trop ancienne
        int expired = bookingSaga.expireUnpaid(Instant.now().plusSeconds(60));

        assertThat(expired).isEqualTo(1);
        assertThat(statusOf(unpaid)).isEqualTo(BookingStatus.CANCELLED);
        assertThat(statusOf(paid)).as("une reservation payee n'expire pas").isEqualTo(BookingStatus.CONFIRMED);
        assertThat(seatLockService.reservedSeats(eventId)).isEqualTo(3);

        outboxRelay.publishPending();
        Message cancelled = rabbitTemplate.receive(RabbitMQConfig.NOTIFICATION_BOOKING_CANCELLED_QUEUE, 5_000);
        assertThat(cancelled).isNotNull();
        assertThat(objectMapper.readTree(cancelled.getBody()).get("reason").asText()).isEqualTo("PAYMENT_TIMEOUT");
    }

    @Test
    @DisplayName("une reservation recente n'est pas expiree")
    void recentBookingIsNotExpired() {
        Booking booking = bookingService.create(eventId, 2, alice);

        assertThat(bookingSaga.expireUnpaid(Instant.now().minus(Duration.ofMinutes(15)))).isZero();

        assertThat(statusOf(booking)).isEqualTo(BookingStatus.PENDING);
        assertThat(seatLockService.reservedSeats(eventId)).isEqualTo(2);
    }

    /** Lit la demande de paiement comme le ferait payment-service. */
    private JsonNode receivePaymentRequest() throws Exception {
        Message request = rabbitTemplate.receive(RabbitMQConfig.PAYMENT_BOOKING_REQUESTED_QUEUE, 5_000);
        assertThat(request).as("booking.requested publie pour payment-service").isNotNull();
        return objectMapper.readTree(request.getBody());
    }

    /** Meme enveloppe que celle ecrite par l'OutboxWriter de payment-service. */
    private void publishPaymentResult(String type, UUID bookingId, String reason) {
        String reasonField = reason == null ? "" : ", \"reason\": \"%s\"".formatted(reason);
        send(type, """
                {
                  "messageId": "%s",
                  "type": "%s",
                  "occurredAt": "2026-08-08T18:52:13.104Z",
                  "paymentId": "%s",
                  "bookingId": "%s",
                  "amount": 50.00%s
                }
                """.formatted(UUID.randomUUID(), type, UUID.randomUUID(), bookingId, reasonField));
    }

    private void send(String routingKey, String body) {
        rabbitTemplate.send("payment.events", routingKey,
                MessageBuilder.withBody(body.getBytes(StandardCharsets.UTF_8))
                        .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                        .setMessageId(UUID.randomUUID().toString())
                        .build());
    }

    private void awaitStatus(Booking booking, BookingStatus expected) {
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(statusOf(booking)).isEqualTo(expected));
    }

    private BookingStatus statusOf(Booking booking) {
        return bookingRepository.findById(booking.getId()).orElseThrow().getStatus();
    }
}
