package com.eventhub.payment.event;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Contrat publie sur la routing key "payment.succeeded" et consomme par booking-service.
 * L'enveloppe (messageId, type, occurredAt) est ajoutee par l'OutboxWriter.
 */
public record PaymentSucceededEvent(UUID paymentId, UUID bookingId, BigDecimal amount) {
}
