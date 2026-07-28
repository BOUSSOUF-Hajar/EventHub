package com.eventhub.booking.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "bookings")
public class Booking {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false)
    private UUID eventId;

    @Column(nullable = false)
    private UUID customerId;

    @Column(nullable = false)
    private Integer seatCount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BookingStatus status;

    @Column(nullable = false)
    private Instant createdAt;

    // TODO (Outbox pattern) : ajouter une table booking_outbox_events alimentee
    // dans la meme transaction que la creation/maj de Booking, puis publiee par
    // un relais (ex: scheduler ou Debezium) vers RabbitMQ. Cela evite le probleme
    // du double-ecrit DB + broker qui ne sont pas atomiques entre eux.

    protected Booking() {
        // requis par JPA
    }

    public Booking(UUID eventId, UUID customerId, Integer seatCount) {
        this.eventId = eventId;
        this.customerId = customerId;
        this.seatCount = seatCount;
        this.status = BookingStatus.PENDING;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getEventId() {
        return eventId;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public Integer getSeatCount() {
        return seatCount;
    }

    public BookingStatus getStatus() {
        return status;
    }

    public void setStatus(BookingStatus status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
