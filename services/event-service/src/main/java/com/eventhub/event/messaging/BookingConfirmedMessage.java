package com.eventhub.event.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.UUID;

/**
 * Vue d'event-service sur "booking.confirmed" : seuls les champs utiles au decompte des
 * places sont declares, les autres sont ignores.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BookingConfirmedMessage(String messageId, UUID eventId, Integer seatCount) {
}
