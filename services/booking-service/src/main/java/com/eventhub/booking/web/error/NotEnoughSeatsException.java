package com.eventhub.booking.web.error;

import java.util.UUID;

/**
 * Levee quand le verrou Redis refuse la reservation faute de places.
 * Traduite en 409 CONFLICT : la demande est valide, c'est l'etat du stock qui la refuse.
 */
public class NotEnoughSeatsException extends RuntimeException {

    public NotEnoughSeatsException(UUID eventId, int requested) {
        super("Plus assez de places disponibles pour l'evenement " + eventId + " (" + requested + " demandee(s))");
    }
}
