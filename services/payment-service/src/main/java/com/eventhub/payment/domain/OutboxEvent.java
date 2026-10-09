package com.eventhub.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.UUID;

/**
 * Ligne d'outbox (PAY-3), ecrite dans la meme transaction que le {@link Payment}.
 *
 * Meme pattern que dans booking-service, volontairement duplique : chaque service est
 * proprietaire de sa base et de son code, une librairie partagee recreerait le couplage
 * que le decoupage en microservices cherche a eviter.
 */
@Entity
@Table(name = "outbox_events", indexes = {
        @Index(name = "idx_outbox_unpublished", columnList = "publishedAt,createdAt")
})
public class OutboxEvent implements Persistable<UUID> {

    @Id
    private UUID id;

    /** Evite le SELECT que merge() ferait avant l'INSERT d'une entite a identifiant assigne. */
    @Transient
    private boolean unsaved = true;

    @Column(nullable = false, length = 64)
    private String aggregateType;

    @Column(nullable = false)
    private UUID aggregateId;

    /** Sert aussi de routing key RabbitMQ, ex: "payment.succeeded". */
    @Column(nullable = false, length = 128)
    private String eventType;

    @Column(nullable = false, columnDefinition = "text")
    private String payload;

    @Column(nullable = false)
    private Instant createdAt;

    /** null tant que l'evenement n'a pas ete publie sur le broker. */
    @Column
    private Instant publishedAt;

    protected OutboxEvent() {
        // requis par JPA
    }

    public OutboxEvent(UUID id, String aggregateType, UUID aggregateId, String eventType, String payload) {
        this.id = id;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payload = payload;
        this.createdAt = Instant.now();
    }

    public void markPublished() {
        this.publishedAt = Instant.now();
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return unsaved;
    }

    @PostPersist
    @PostLoad
    void markTracked() {
        this.unsaved = false;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public UUID getAggregateId() {
        return aggregateId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getPayload() {
        return payload;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }
}
