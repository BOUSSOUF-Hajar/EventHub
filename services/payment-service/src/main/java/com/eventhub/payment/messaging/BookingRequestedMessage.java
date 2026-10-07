package com.eventhub.payment.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Vue de payment-service sur l'evenement "booking.requested".
 *
 * On ne declare que les champs utiles au paiement et on ignore les autres : booking-service
 * peut ainsi enrichir son evenement sans casser ce consommateur ("tolerant reader").
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BookingRequestedMessage(String messageId, UUID bookingId, BigDecimal amount) {
}
