package com.eventhub.event.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Trace d'un message deja applique, cle = messageId de l'evenement recu.
 *
 * Ecrite dans la meme transaction que la mise a jour des places : soit les deux sont
 * validees, soit aucune. C'est ce qui rend le decompte idempotent face aux relivraisons
 * de RabbitMQ, la cle primaire servant de garde-fou si deux copies arrivent en parallele.
 */
@Entity
@Table(name = "processed_messages")
public class ProcessedMessage {

    @Id
    @Column(length = 64)
    private String messageId;

    @Column(nullable = false)
    private Instant processedAt;

    protected ProcessedMessage() {
        // requis par JPA
    }

    public ProcessedMessage(String messageId) {
        this.messageId = messageId;
        this.processedAt = Instant.now();
    }

    public String getMessageId() {
        return messageId;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }
}
