package com.eventhub.booking.outbox;

import com.eventhub.booking.config.EventHubRabbitProperties;
import com.eventhub.booking.domain.OutboxEvent;
import com.eventhub.booking.repository.OutboxEventRepository;
import com.eventhub.booking.service.BookingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Relais du pattern Outbox : lit les evenements non publies et les envoie sur RabbitMQ.
 *
 * Garantie : <b>at least once</b>. Un evenement n'est marque publie que si le broker l'a
 * confirme <i>et</i> ne l'a pas renvoye faute de destinataire ; en cas de doute il repart
 * au prochain passage. Les consommateurs doivent donc etre idempotents — d'ou le champ
 * "messageId" present dans chaque payload.
 *
 * <h2>Pourquoi des confirmations correlees</h2>
 * Un ACK ne prouve que la prise en charge par l'exchange : un message qu'aucune queue
 * n'accepte est acquitte puis jete. Il faut donc croiser l'ACK avec le "retour" declenche
 * par le flag mandatory. Spring AMQP garantit qu'en mode correle, le retour est attache a
 * la {@link CorrelationData} <i>avant</i> que son future ne se termine : le controle est
 * deterministe, la ou un callback global expose a une course entre threads.
 *
 * <h2>Cout assume</h2>
 * La transaction reste ouverte pendant l'aller-retour avec le broker : c'est inherent au
 * pattern, marquer "publie" doit se faire sous le meme verrou que la lecture du lot. Le
 * risque de monopoliser le pool JDBC est borne par {@code confirm-timeout-ms} et par la
 * taille du lot ; le relais n'utilise qu'une connexion a la fois.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxEventRepository outboxEventRepository;
    private final RabbitTemplate rabbitTemplate;
    private final BookingService bookingService;
    private final EventHubRabbitProperties rabbitProperties;
    private final int batchSize;
    private final long confirmTimeoutMs;

    public OutboxRelay(OutboxEventRepository outboxEventRepository,
                       RabbitTemplate rabbitTemplate,
                       BookingService bookingService,
                       EventHubRabbitProperties rabbitProperties,
                       @Value("${eventhub.outbox.batch-size:100}") int batchSize,
                       @Value("${eventhub.outbox.confirm-timeout-ms:5000}") long confirmTimeoutMs) {
        this.outboxEventRepository = outboxEventRepository;
        this.rabbitTemplate = rabbitTemplate;
        this.bookingService = bookingService;
        this.rabbitProperties = rabbitProperties;
        this.batchSize = batchSize;
        this.confirmTimeoutMs = confirmTimeoutMs;
    }

    @Scheduled(
            fixedDelayString = "${eventhub.outbox.poll-interval-ms:1000}",
            initialDelayString = "${eventhub.outbox.initial-delay-ms:1000}")
    @Transactional
    public void publishPending() {
        List<OutboxEvent> batch = outboxEventRepository.lockUnpublishedBatch(batchSize);
        if (batch.isEmpty()) {
            return;
        }

        Map<OutboxEvent, CorrelationData> inFlight = new LinkedHashMap<>();
        for (OutboxEvent event : batch) {
            CorrelationData correlation = new CorrelationData(event.getId().toString());
            rabbitTemplate.send(rabbitProperties.exchange(), event.getEventType(), toMessage(event), correlation);
            inFlight.put(event, correlation);
        }

        List<OutboxEvent> delivered = new ArrayList<>();
        int rejected = 0;
        for (Map.Entry<OutboxEvent, CorrelationData> entry : inFlight.entrySet()) {
            if (isDelivered(entry.getKey(), entry.getValue())) {
                delivered.add(entry.getKey());
            } else {
                rejected++;
            }
        }

        // Les evenements refuses restent sans publishedAt : on ne leve pas, sinon ceux du
        // meme lot qui sont bien partis seraient republies en double au passage suivant.
        delivered.forEach(event -> {
            event.markPublished();
            // Une fois la demande reellement partie, la reservation attend son paiement.
            if (rabbitProperties.routingKey().requested().equals(event.getEventType())) {
                bookingService.markAwaitingPayment(event.getAggregateId());
            }
        });

        if (rejected > 0) {
            log.error("{} evenement(s) d'outbox non delivre(s), conserve(s) pour republication", rejected);
        }
        if (!delivered.isEmpty()) {
            log.info("{} evenement(s) d'outbox publie(s)", delivered.size());
        }
    }

    /**
     * Une absence de reponse du broker n'est pas un refus : on leve pour annuler la
     * transaction et retenter le lot entier, plutot que d'abandonner des evenements
     * dont on ignore le sort.
     */
    private boolean isDelivered(OutboxEvent event, CorrelationData correlation) {
        CorrelationData.Confirm confirm;
        try {
            confirm = correlation.getFuture().get(confirmTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Attente de confirmation interrompue pour " + event.getId(), e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException(
                    "RabbitMQ n'a pas confirme la publication de l'evenement " + event.getId(), e);
        }

        if (!confirm.isAck()) {
            log.error("Evenement {} refuse par RabbitMQ : {}", event.getId(), confirm.getReason());
            return false;
        }
        if (correlation.getReturned() != null) {
            log.error("Evenement {} non routable (routing key '{}') : aucune queue bindee sur l'exchange {}",
                    event.getId(), event.getEventType(), rabbitProperties.exchange());
            return false;
        }
        return true;
    }

    private Message toMessage(OutboxEvent event) {
        return MessageBuilder
                .withBody(event.getPayload().getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setContentEncoding(StandardCharsets.UTF_8.name())
                // messageId = cle de deduplication cote consommateur
                .setMessageId(event.getId().toString())
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .build();
    }
}
