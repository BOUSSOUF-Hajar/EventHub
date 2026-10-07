package com.eventhub.booking.event;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Contrat publie sur la routing key "booking.confirmed" : fin heureuse de la Saga.
 * Consomme par notification-service (email de confirmation).
 */
public record BookingConfirmedEvent(
        UUID bookingId,
        UUID eventId,
        UUID customerId,
        String customerEmail,
        Integer seatCount,
        BigDecimal amount) {
}
