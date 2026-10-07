package com.eventhub.booking.event;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Contrat publie sur la routing key "booking.requested" et consomme par payment-service.
 *
 * Les champs de l'enveloppe (messageId, type, occurredAt) sont ajoutes par
 * {@link com.eventhub.booking.service.OutboxWriter} : ce record ne porte que le metier.
 */
public record BookingRequestedEvent(
        UUID bookingId,
        UUID eventId,
        UUID customerId,
        String customerEmail,
        Integer seatCount,
        BigDecimal amount) {
}
