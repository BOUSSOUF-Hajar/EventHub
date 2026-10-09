package com.eventhub.notification;

import com.eventhub.notification.config.RabbitMQConfig;
import com.eventhub.notification.service.ProcessedEventStore;
import com.eventhub.notification.service.ProcessedEventStore.Claim;
import com.eventhub.notification.support.AbstractIntegrationTest;
import com.eventhub.notification.support.MailhogClient;
import com.eventhub.notification.support.MailhogClient.ReceivedEmail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Definition of Done du lot 4 : un evenement publie comme le fait booking-service aboutit
 * a un email reellement recu par Mailhog.
 */
class NotificationFlowIntegrationTest extends AbstractIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private RabbitAdmin rabbitAdmin;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private ProcessedEventStore processedEvents;

    private final MailhogClient mailhog = new MailhogClient(mailhogApiUrl());

    /** Une adresse par test : les assertions ne dependent pas des emails des autres tests. */
    private String recipient;

    @BeforeEach
    void reset() {
        rabbitAdmin.purgeQueue(RabbitMQConfig.BOOKING_CONFIRMED_QUEUE, false);
        rabbitAdmin.purgeQueue(RabbitMQConfig.BOOKING_CANCELLED_QUEUE, false);
        redisTemplate.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushAll();
            return null;
        });
        mailhog.deleteAll();
        recipient = "client-" + UUID.randomUUID() + "@example.com";
    }

    @Test
    @DisplayName("NTF-1 : booking.confirmed -> email de confirmation recu par Mailhog")
    void confirmedBookingSendsAConfirmationEmail() {
        UUID bookingId = UUID.randomUUID();

        publishConfirmed(UUID.randomUUID().toString(), bookingId, recipient);

        ReceivedEmail email = awaitSingleEmailTo(recipient);
        assertThat(email.from()).isEqualTo("no-reply@eventhub.test");
        assertThat(email.subject()).isEqualTo("Votre réservation est confirmée");
        assertThat(email.body())
                .contains(bookingId.toString())
                .contains("Nombre de places : 2")
                .contains("Montant payé : 50.00 EUR");
    }

    @Test
    @DisplayName("NTF-2 : booking.cancelled -> email d'annulation avec le motif")
    void cancelledBookingSendsACancellationEmail() {
        UUID bookingId = UUID.randomUUID();

        publishCancelled(UUID.randomUUID().toString(), bookingId, recipient, "PAYMENT_FAILED");

        ReceivedEmail email = awaitSingleEmailTo(recipient);
        assertThat(email.subject()).isEqualTo("Votre réservation a été annulée");
        assertThat(email.body())
                .contains(bookingId.toString())
                .contains("le paiement a été refusé");
    }

    @Test
    @DisplayName("NTF-3 : le meme evenement recu deux fois n'envoie qu'un email")
    void duplicateEventSendsASingleEmail() {
        String messageId = UUID.randomUUID().toString();
        UUID bookingId = UUID.randomUUID();
        String sentinelRecipient = "sentinelle-" + UUID.randomUUID() + "@example.com";

        publishConfirmed(messageId, bookingId, recipient);
        publishConfirmed(messageId, bookingId, recipient);
        // Sentinelle : queue FIFO et listener mono-thread, donc une fois son email recu,
        // les deux messages precedents ont ete traites.
        publishConfirmed(UUID.randomUUID().toString(), UUID.randomUUID(), sentinelRecipient);
        awaitSingleEmailTo(sentinelRecipient);

        assertThat(mailhog.emailsTo(recipient)).hasSize(1);
    }

    @Test
    @DisplayName("deux evenements distincts de la meme reservation donnent deux emails")
    void deduplicationIsPerEventNotPerBooking() {
        UUID bookingId = UUID.randomUUID();

        publishConfirmed(UUID.randomUUID().toString(), bookingId, recipient);
        publishCancelled(UUID.randomUUID().toString(), bookingId, recipient, "PAYMENT_TIMEOUT");

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(mailhog.emailsTo(recipient))
                .extracting(ReceivedEmail::subject)
                .containsExactlyInAnyOrder("Votre réservation est confirmée", "Votre réservation a été annulée"));
    }

    @Test
    @DisplayName("un message illisible ou incomplet est ecarte sans bloquer les suivants")
    void poisonMessagesDoNotBlockTheQueue() {
        send("booking.confirmed", "ceci n'est pas du JSON");
        send("booking.confirmed", "{\"messageId\":\"m-1\",\"bookingId\":\"%s\"}".formatted(UUID.randomUUID()));

        publishConfirmed(UUID.randomUUID().toString(), UUID.randomUUID(), recipient);

        // Si les messages invalides etaient retentes ou remis en queue, celui-ci attendrait.
        awaitSingleEmailTo(recipient);
        assertThat(mailhog.emails()).hasSize(1);
        assertThat(rabbitAdmin.getQueueInfo(RabbitMQConfig.BOOKING_CONFIRMED_QUEUE).getMessageCount()).isZero();
    }

    @Test
    @DisplayName("un evenement reserve par un traitement plante est repris a l'expiration du bail")
    void eventHeldByACrashedConsumerIsEventuallyDelivered() {
        String messageId = UUID.randomUUID().toString();
        // Simule une instance qui a reserve l'evenement puis plante avant d'envoyer l'email.
        assertThat(processedEvents.claim(messageId)).isEqualTo(Claim.ACQUIRED);

        publishConfirmed(messageId, UUID.randomUUID(), recipient);

        // Bail de 2 s en test : le message est retente puis remis en queue jusqu'a pouvoir
        // reprendre l'evenement. Ni perdu, ni envoye pendant que le bail court.
        awaitSingleEmailTo(recipient);
        assertThat(processedEvents.claim(messageId)).isEqualTo(Claim.DUPLICATE);
    }

    @Test
    @DisplayName("la reservation d'un evenement est exclusive tant qu'il n'est pas marque traite")
    void claimIsExclusiveUntilDone() {
        String eventId = UUID.randomUUID().toString();

        assertThat(processedEvents.claim(eventId)).isEqualTo(Claim.ACQUIRED);
        assertThat(processedEvents.claim(eventId)).isEqualTo(Claim.IN_PROGRESS);

        processedEvents.markDone(eventId);
        assertThat(processedEvents.claim(eventId)).isEqualTo(Claim.DUPLICATE);

        String released = UUID.randomUUID().toString();
        processedEvents.claim(released);
        processedEvents.release(released);
        assertThat(processedEvents.claim(released)).as("apres un echec d'envoi").isEqualTo(Claim.ACQUIRED);
    }

    /** Meme enveloppe que celle ecrite par l'OutboxWriter de booking-service. */
    private void publishConfirmed(String messageId, UUID bookingId, String customerEmail) {
        send("booking.confirmed", """
                {
                  "messageId": "%s",
                  "type": "booking.confirmed",
                  "occurredAt": "2026-10-07T20:37:39.104Z",
                  "bookingId": "%s",
                  "eventId": "%s",
                  "customerId": "%s",
                  "customerEmail": "%s",
                  "seatCount": 2,
                  "amount": 50.00
                }
                """.formatted(messageId, bookingId, UUID.randomUUID(), UUID.randomUUID(), customerEmail));
    }

    private void publishCancelled(String messageId, UUID bookingId, String customerEmail, String reason) {
        send("booking.cancelled", """
                {
                  "messageId": "%s",
                  "type": "booking.cancelled",
                  "occurredAt": "2026-10-07T20:38:17.866Z",
                  "bookingId": "%s",
                  "eventId": "%s",
                  "customerId": "%s",
                  "customerEmail": "%s",
                  "seatCount": 2,
                  "reason": "%s"
                }
                """.formatted(messageId, bookingId, UUID.randomUUID(), UUID.randomUUID(), customerEmail, reason));
    }

    private void send(String routingKey, String body) {
        rabbitTemplate.send("booking.events", routingKey,
                MessageBuilder.withBody(body.getBytes(StandardCharsets.UTF_8))
                        .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                        .setMessageId(UUID.randomUUID().toString())
                        .build());
    }

    private ReceivedEmail awaitSingleEmailTo(String to) {
        List<ReceivedEmail> emails = await().atMost(TIMEOUT)
                .until(() -> mailhog.emailsTo(to), found -> !found.isEmpty());
        assertThat(emails).hasSize(1);
        return emails.get(0);
    }
}
