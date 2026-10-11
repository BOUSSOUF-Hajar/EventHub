package com.eventhub.notification.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Vue de notification-service sur "booking.confirmed" et "booking.cancelled".
 *
 * Un seul record pour les deux evenements : {@code amount} n'est renseigne que pour une
 * confirmation, {@code reason} que pour une annulation. Les champs inconnus sont ignores,
 * booking-service peut enrichir ses evenements sans casser ce consommateur.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BookingEventMessage(
        String messageId,
        UUID bookingId,
        String customerEmail,
        Integer seatCount,
        BigDecimal amount,
        String reason) {
}
