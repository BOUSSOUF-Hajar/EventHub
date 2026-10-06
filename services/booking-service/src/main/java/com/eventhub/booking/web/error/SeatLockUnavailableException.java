package com.eventhub.booking.web.error;

import java.util.UUID;

/**
 * Redis n'a pas pu arbitrer la disponibilite.
 *
 * Distincte de {@link NotEnoughSeatsException} a dessein : annoncer "complet" a un
 * client alors que le verrou est simplement en panne serait un mensonge, et masquerait
 * l'incident au lieu de le signaler.
 */
public class SeatLockUnavailableException extends RuntimeException {

    public SeatLockUnavailableException(UUID eventId, Throwable cause) {
        super("Verrou de disponibilite indisponible pour l'evenement " + eventId, cause);
    }

    public SeatLockUnavailableException(UUID eventId) {
        this(eventId, null);
    }
}
