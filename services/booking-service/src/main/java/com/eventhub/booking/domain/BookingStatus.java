package com.eventhub.booking.domain;

/**
 * Etats de la machine a etats de la Saga "reservation".
 *
 * PENDING        -> place verrouillee dans Redis, en attente de paiement
 * AWAITING_PAYMENT -> evenement "booking.requested" publie, paiement en cours
 * CONFIRMED      -> paiement reussi, place definitivement attribuee
 * CANCELLED      -> paiement echoue ou expiration du verrou : place liberee (action compensatoire)
 */
public enum BookingStatus {
    PENDING,
    AWAITING_PAYMENT,
    CONFIRMED,
    CANCELLED
}
