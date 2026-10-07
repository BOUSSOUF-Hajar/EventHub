package com.eventhub.payment.service;

import com.eventhub.payment.domain.OutboxEvent;
import com.eventhub.payment.repository.OutboxEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Ajoute une ligne d'outbox dans la transaction courante.
 *
 * Pas de @Transactional ici, volontairement : la methode DOIT s'executer dans la
 * transaction qui persiste le paiement, sinon l'atomicite du pattern Outbox tombe.
 */
@Component
public class OutboxWriter {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    public OutboxWriter(OutboxEventRepository outboxEventRepository, ObjectMapper objectMapper) {
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
    }

    public OutboxEvent append(String aggregateType, UUID aggregateId, String eventType, Object payload) {
        UUID messageId = UUID.randomUUID();

        // Meme enveloppe a plat que booking-service : messageId sert de cle de
        // deduplication aux consommateurs, qui recoivent une garantie "at least once".
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("messageId", messageId.toString());
        body.put("type", eventType);
        body.put("occurredAt", Instant.now().toString());
        body.putAll(objectMapper.convertValue(payload, MAP_TYPE));

        String json;
        try {
            json = objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Serialisation impossible de l'evenement " + eventType, e);
        }

        return outboxEventRepository.save(
                new OutboxEvent(messageId, aggregateType, aggregateId, eventType, json));
    }
}
