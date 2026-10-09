package com.eventhub.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Tentative de paiement d'une reservation.
 *
 * <h2>Idempotence (PAY-2)</h2>
 * La cle d'idempotence est {@code bookingId}, protegee par une contrainte unique : une
 * reservation ne se paie qu'une fois. On ne s'appuie pas sur le messageId du message
 * entrant, qui ne couvrirait que la relivraison d'un meme message par RabbitMQ ; avec
 * bookingId, une demande reemise sous un autre identifiant est ecartee elle aussi.
 *
 * La contrainte en base est le vrai garde-fou : la verification "existe deja ?" faite
 * avant traitement n'est qu'un raccourci, elle ne protege pas de deux instances qui
 * traiteraient le meme message au meme instant.
 */
@Entity
@Table(name = "payments", uniqueConstraints = {
        @UniqueConstraint(name = "uk_payments_booking_id", columnNames = "bookingId")
})
public class Payment {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false)
    private UUID bookingId;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private PaymentStatus status;

    /** Motif du refus, null pour un paiement reussi. */
    @Column(length = 64)
    private String failureReason;

    /** messageId du "booking.requested" a l'origine du paiement, pour la tracabilite. */
    @Column(length = 64)
    private String requestMessageId;

    @Column(nullable = false)
    private Instant createdAt;

    protected Payment() {
        // requis par JPA
    }

    private Payment(UUID bookingId, BigDecimal amount, PaymentStatus status,
                    String failureReason, String requestMessageId) {
        this.bookingId = bookingId;
        this.amount = amount;
        this.status = status;
        this.failureReason = failureReason;
        this.requestMessageId = requestMessageId;
        this.createdAt = Instant.now();
    }

    public static Payment succeeded(UUID bookingId, BigDecimal amount, String requestMessageId) {
        return new Payment(bookingId, amount, PaymentStatus.SUCCEEDED, null, requestMessageId);
    }

    public static Payment failed(UUID bookingId, BigDecimal amount, String failureReason, String requestMessageId) {
        return new Payment(bookingId, amount, PaymentStatus.FAILED, failureReason, requestMessageId);
    }

    public UUID getId() {
        return id;
    }

    public UUID getBookingId() {
        return bookingId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public String getRequestMessageId() {
        return requestMessageId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
