package com.eventhub.booking.domain;

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
 * Ligne d'outbox (pattern Outbox).
 *
 * Pourquoi : publier vers RabbitMQ et ecrire en base sont deux systemes distincts,
 * on ne peut pas les rendre atomiques. Si on publie puis que le commit echoue, on a
 * annonce un evenement qui n'a jamais eu lieu ; si on commit puis que la publication
 * echoue, on perd l'evenement. On ecrit donc l'evenement dans CETTE table, dans la
 * meme transaction que l'agregat, et un relais separe se charge de la publication
 * avec une garantie "at least once" (d'ou l'exigence d'idempotence cote consommateur).
 *
 * L'identifiant est genere en Java et non par la base : il est ainsi connu avant le
 * flush, ce qui permet de l'inclure dans le payload et de s'en servir comme cle de
 * deduplication cote consommateur.
 */
@Entity
@Table(name = "outbox_events", indexes = {
        @Index(name = "idx_outbox_unpublished", columnList = "publishedAt,createdAt")
})
public class OutboxEvent implements Persistable<UUID> {

    @Id
    private UUID id;

    /**
     * Avec un identifiant assigne par l'application, Spring Data considere par defaut
     * l'entite comme detachee et passe par merge(), soit un SELECT inutile avant chaque
     * INSERT -- en pleine transaction critique de reservation. Ce drapeau lui indique
     * qu'une instance fraichement construite doit partir directement en persist().
     */
    @Transient
    private boolean unsaved = true;

    /** Type d'agregat source, ex: "booking". Utile pour tracer/filtrer. */
    @Column(nullable = false, length = 64)
    private String aggregateType;

    @Column(nullable = false)
    private UUID aggregateId;

    /** Sert aussi de routing key RabbitMQ, ex: "booking.requested". */
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
