package com.eventhub.payment.outbox;

import com.eventhub.payment.config.PaymentRabbitProperties;
import com.eventhub.payment.domain.OutboxEvent;
import com.eventhub.payment.repository.OutboxEventRepository;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Relais du pattern Outbox (PAY-3) : publie les resultats de paiement sur RabbitMQ.
 *
 * Meme mecanique que le relais de booking-service : garantie "at least once", un
 * evenement n'est marque publie que si le broker l'a confirme ET ne l'a pas renvoye
 * faute de queue destinataire (confirmations correlees + flag mandatory).
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxEventRepository outboxEventRepository;
    private final RabbitTemplate rabbitTemplate;
    private final PaymentRabbitProperties rabbitProperties;
    private final int batchSize;
    private final long confirmTimeoutMs;

    public OutboxRelay(OutboxEventRepository outboxEventRepository,
                       RabbitTemplate rabbitTemplate,
                       PaymentRabbitProperties rabbitProperties,
                       @Value("${eventhub.outbox.batch-size:100}") int batchSize,
                       @Value("${eventhub.outbox.confirm-timeout-ms:5000}") long confirmTimeoutMs) {
        this.outboxEventRepository = outboxEventRepository;
        this.rabbitTemplate = rabbitTemplate;
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

        // Les evenements refuses restent sans publishedAt : on ne leve pas, sinon ceux du
        // meme lot qui sont bien partis seraient republies en double au passage suivant.
        int delivered = 0;
        for (Map.Entry<OutboxEvent, CorrelationData> entry : inFlight.entrySet()) {
            if (isDelivered(entry.getKey(), entry.getValue())) {
                entry.getKey().markPublished();
                delivered++;
            }
        }

        int rejected = batch.size() - delivered;
        if (rejected > 0) {
            log.error("{} evenement(s) d'outbox non delivre(s), conserve(s) pour republication", rejected);
        }
        if (delivered > 0) {
            log.info("{} evenement(s) d'outbox publie(s)", delivered);
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
