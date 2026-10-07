package com.eventhub.booking.domain;

import java.util.Set;

/**
 * Etats de la machine a etats de la Saga "reservation".
 *
 * PENDING          -> places verrouillees dans Redis, reservation persistee
 * AWAITING_PAYMENT -> evenement "booking.requested" publie, paiement en cours
 * CONFIRMED        -> paiement reussi, places definitivement attribuees
 * CANCELLED        -> paiement echoue ou expiration : places liberees (action compensatoire)
 *
 * CONFIRMED et CANCELLED sont terminaux : une fois atteints, plus aucune
 * transition n'est acceptee. C'est ce qui protege la Saga contre un message
 * en retard (ex: "payment.failed" recu apres un "payment.succeeded").
 */
public enum BookingStatus {
    PENDING,
    AWAITING_PAYMENT,
    CONFIRMED,
    CANCELLED;

    public boolean isTerminal() {
        return this == CONFIRMED || this == CANCELLED;
    }

    public boolean canTransitionTo(BookingStatus target) {
        return switch (this) {
            case PENDING -> Set.of(AWAITING_PAYMENT, CONFIRMED, CANCELLED).contains(target);
            case AWAITING_PAYMENT -> Set.of(CONFIRMED, CANCELLED).contains(target);
            case CONFIRMED, CANCELLED -> false;
        };
    }
}
