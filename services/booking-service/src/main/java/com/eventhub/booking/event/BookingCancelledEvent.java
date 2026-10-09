package com.eventhub.booking.event;

import java.util.UUID;

/**
 * Contrat publie sur la routing key "booking.cancelled" une fois la compensation faite.
 *
 * @param reason "PAYMENT_FAILED" ou "PAYMENT_TIMEOUT" : permet au consommateur d'adapter
 *               son message sans avoir a connaitre payment-service
 */
public record BookingCancelledEvent(
        UUID bookingId,
        UUID eventId,
        UUID customerId,
        String customerEmail,
        Integer seatCount,
        String reason) {
}
