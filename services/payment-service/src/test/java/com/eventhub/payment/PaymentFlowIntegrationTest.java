package com.eventhub.payment;

import com.eventhub.payment.config.RabbitMQConfig;
import com.eventhub.payment.domain.OutboxEvent;
import com.eventhub.payment.domain.Payment;
import com.eventhub.payment.domain.PaymentStatus;
import com.eventhub.payment.gateway.PaymentGateway;
import com.eventhub.payment.gateway.PaymentGateway.ChargeResult;
import com.eventhub.payment.outbox.OutboxRelay;
import com.eventhub.payment.repository.OutboxEventRepository;
import com.eventhub.payment.repository.PaymentRepository;
import com.eventhub.payment.support.AbstractIntegrationTest;
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

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

/**
 * Moitie "paiement" de la Saga, a travers un vrai broker : un "booking.requested" entre
 * par RabbitMQ exactement comme booking-service le publie, et on verifie ce qui ressort
 * sur la queue que booking-service consomme.
 *
 * Seul le PSP est simule : c'est la seule dependance exterieure du service, et la piloter
 * rend le chemin d'echec deterministe (le tirage aleatoire a son propre test unitaire).
 */
class PaymentFlowIntegrationTest extends AbstractIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Autowired
    private PaymentRepository paymentRepository;

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
    private PaymentGateway paymentGateway;

    @BeforeEach
    void reset() {
        outboxEventRepository.deleteAll();
        paymentRepository.deleteAll();
        rabbitAdmin.purgeQueue(RabbitMQConfig.BOOKING_REQUESTED_QUEUE, false);
        rabbitAdmin.purgeQueue(RabbitMQConfig.PAYMENT_RESULT_QUEUE, false);
        given(paymentGateway.charge(any(), any())).willReturn(ChargeResult.approve());
    }

    @Test
    @DisplayName("booking.requested -> paiement reussi -> payment.succeeded publie pour booking-service")
    void acceptedPaymentPublishesPaymentSucceeded() throws Exception {
        UUID bookingId = UUID.randomUUID();

        publishBookingRequested(bookingId, "50.00");

        Payment payment = awaitPayment(bookingId);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(payment.getAmount()).isEqualByComparingTo("50.00");
        then(paymentGateway).should().charge(eq(bookingId), any(BigDecimal.class));

        outboxRelay.publishPending();

        Message result = rabbitTemplate.receive(RabbitMQConfig.PAYMENT_RESULT_QUEUE, 5_000);
        assertThat(result).as("resultat recu sur la queue de booking-service").isNotNull();
        assertThat(result.getMessageProperties().getReceivedRoutingKey()).isEqualTo("payment.succeeded");

        JsonNode payload = objectMapper.readTree(result.getBody());
        assertThat(payload.get("type").asText()).isEqualTo("payment.succeeded");
        assertThat(payload.get("messageId").asText()).isEqualTo(result.getMessageProperties().getMessageId());
        assertThat(payload.get("bookingId").asText()).isEqualTo(bookingId.toString());
        assertThat(payload.get("paymentId").asText()).isEqualTo(payment.getId().toString());
        assertThat(payload.get("amount").decimalValue()).isEqualByComparingTo("50.00");

        assertThat(outboxEventRepository.findByAggregateIdOrderByCreatedAtAsc(payment.getId()))
                .singleElement()
                .satisfies(event -> assertThat(event.getPublishedAt()).isNotNull());
    }

    @Test
    @DisplayName("booking.requested -> paiement refuse -> payment.failed publie avec son motif")
    void declinedPaymentPublishesPaymentFailed() throws Exception {
        given(paymentGateway.charge(any(), any())).willReturn(ChargeResult.decline("CARD_DECLINED"));
        UUID bookingId = UUID.randomUUID();

        publishBookingRequested(bookingId, "30.00");

        Payment payment = awaitPayment(bookingId);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.getFailureReason()).isEqualTo("CARD_DECLINED");

        outboxRelay.publishPending();

        Message result = rabbitTemplate.receive(RabbitMQConfig.PAYMENT_RESULT_QUEUE, 5_000);
        assertThat(result).isNotNull();
        assertThat(result.getMessageProperties().getReceivedRoutingKey()).isEqualTo("payment.failed");

        JsonNode payload = objectMapper.readTree(result.getBody());
        assertThat(payload.get("type").asText()).isEqualTo("payment.failed");
        assertThat(payload.get("bookingId").asText()).isEqualTo(bookingId.toString());
        assertThat(payload.get("reason").asText()).isEqualTo("CARD_DECLINED");
    }

    @Test
    @DisplayName("PAY-2 : le meme evenement recu deux fois ne debite qu'une fois")
    void duplicateDeliveryChargesOnlyOnce() {
        UUID bookingId = UUID.randomUUID();
        String messageId = UUID.randomUUID().toString();

        publishBookingRequested(bookingId, "50.00", messageId);
        publishBookingRequested(bookingId, "50.00", messageId);
        // Sentinelle : la queue est FIFO et le listener mono-thread, donc une fois ce
        // message traite, les deux precedents l'ont ete aussi.
        UUID sentinel = UUID.randomUUID();
        publishBookingRequested(sentinel, "10.00");
        awaitPayment(sentinel);

        assertThat(paymentRepository.findAll())
                .filteredOn(payment -> payment.getBookingId().equals(bookingId))
                .hasSize(1);
        then(paymentGateway).should(times(1)).charge(eq(bookingId), any(BigDecimal.class));
        // un seul resultat a publier : sinon booking-service recevrait deux verdicts
        assertThat(outboxEventRepository.findAll()).extracting(OutboxEvent::getEventType)
                .containsExactly("payment.succeeded", "payment.succeeded"); // reservation + sentinelle
    }

    @Test
    @DisplayName("un message illisible est ecarte sans bloquer les messages suivants")
    void poisonMessageDoesNotBlockTheQueue() {
        send("ceci n'est pas du JSON".getBytes(StandardCharsets.UTF_8), UUID.randomUUID().toString());
        send("{\"messageId\":\"x\",\"amount\":50.00}".getBytes(StandardCharsets.UTF_8), "sans-booking-id");

        UUID bookingId = UUID.randomUUID();
        publishBookingRequested(bookingId, "50.00");

        // Si les messages invalides etaient remis en queue, celui-ci ne passerait jamais.
        awaitPayment(bookingId);
        assertThat(paymentRepository.count()).isEqualTo(1);
        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(rabbitAdmin.getQueueInfo(RabbitMQConfig.BOOKING_REQUESTED_QUEUE).getMessageCount())
                        .as("aucun message empoisonne ne tourne en boucle")
                        .isZero());
    }

    private void publishBookingRequested(UUID bookingId, String amount) {
        publishBookingRequested(bookingId, amount, UUID.randomUUID().toString());
    }

    /** Reproduit le message de l'outbox de booking-service, champs non utilises compris. */
    private void publishBookingRequested(UUID bookingId, String amount, String messageId) {
        String body = """
                {
                  "messageId": "%s",
                  "type": "booking.requested",
                  "occurredAt": "2026-08-08T18:52:12.618768900Z",
                  "bookingId": "%s",
                  "eventId": "%s",
                  "customerId": "%s",
                  "customerEmail": "alice@example.com",
                  "seatCount": 2,
                  "amount": %s
                }
                """.formatted(messageId, bookingId, UUID.randomUUID(), UUID.randomUUID(), amount);
        send(body.getBytes(StandardCharsets.UTF_8), messageId);
    }

    private void send(byte[] body, String messageId) {
        rabbitTemplate.send("booking.events", "booking.requested", MessageBuilder.withBody(body)
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setMessageId(messageId)
                .build());
    }

    private Payment awaitPayment(UUID bookingId) {
        return await().atMost(TIMEOUT)
                .until(() -> paymentRepository.findByBookingId(bookingId).orElse(null), payment -> payment != null);
    }
}
