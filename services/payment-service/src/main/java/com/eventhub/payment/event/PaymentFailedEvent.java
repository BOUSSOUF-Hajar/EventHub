package com.eventhub.payment.event;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Contrat publie sur la routing key "payment.failed" : c'est lui qui declenche la
 * compensation (annulation + liberation des places) dans booking-service.
 */
public record PaymentFailedEvent(UUID paymentId, UUID bookingId, BigDecimal amount, String reason) {
}
