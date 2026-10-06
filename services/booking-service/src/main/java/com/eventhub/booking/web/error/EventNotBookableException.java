package com.eventhub.booking.web.error;

import java.util.UUID;

/**
 * Levee quand l'evenement existe mais ne peut pas etre reserve (deja commence, prix non
 * renseigne). Traduite en 409 CONFLICT : la demande est valide, c'est l'etat de
 * l'evenement qui la refuse.
 */
public class EventNotBookableException extends RuntimeException {

    public EventNotBookableException(UUID eventId, String reason) {
        super("L'evenement " + eventId + " n'est pas reservable : " + reason);
    }
}
