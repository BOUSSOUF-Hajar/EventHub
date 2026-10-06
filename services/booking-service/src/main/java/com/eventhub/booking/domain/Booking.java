package com.eventhub.booking.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Agregat "reservation". C'est lui qui porte l'etat de la Saga.
 *
 * Les evenements metier ne sont jamais publies directement depuis cette classe :
 * ils passent par {@link OutboxEvent}, ecrit dans la meme transaction, ce qui
 * evite le double-ecrit non atomique "base + broker".
 */
@Entity
@Table(name = "bookings", indexes = {
        @Index(name = "idx_bookings_customer_id", columnList = "customerId"),
        @Index(name = "idx_bookings_status_created_at", columnList = "status,createdAt")
})
public class Booking {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false)
    private UUID eventId;

    @Column(nullable = false)
    private UUID customerId;

    @Column(nullable = false)
    private String customerEmail;

    @Column(nullable = false)
    private Integer seatCount;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private BookingStatus status;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected Booking() {
        // requis par JPA
    }

    public Booking(UUID eventId, UUID customerId, String customerEmail, Integer seatCount, BigDecimal totalAmount) {
        this.eventId = eventId;
        this.customerId = customerId;
        this.customerEmail = customerEmail;
        this.seatCount = seatCount;
        this.totalAmount = totalAmount;
        this.status = BookingStatus.PENDING;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    /**
     * Transition d'etat controlee : on refuse les transitions incoherentes plutot
     * que d'exposer un setter libre. Un message RabbitMQ pouvant etre redelivre,
     * une transition deja appliquee est ignoree silencieusement (idempotence).
     *
     * @return true si l'etat a change, false si la transition etait un doublon
     */
    public boolean transitionTo(BookingStatus target) {
        if (this.status == target) {
            return false;
        }
        if (!this.status.canTransitionTo(target)) {
            throw new IllegalStateException(
                    "Transition interdite " + this.status + " -> " + target + " pour la reservation " + id);
        }
        this.status = target;
        this.updatedAt = Instant.now();
        return true;
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

    public String getCustomerEmail() {
        return customerEmail;
    }

    public Integer getSeatCount() {
        return seatCount;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public BookingStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
