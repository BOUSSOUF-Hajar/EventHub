package com.eventhub.booking.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.UUID;

/**
 * Vue de booking-service sur "payment.succeeded" et "payment.failed".
 *
 * Seuls les champs utiles a la Saga sont declares, les autres sont ignores :
 * payment-service peut enrichir ses evenements sans casser ce consommateur.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PaymentResultMessage(String messageId, String type, UUID bookingId, String reason) {
}
