package com.eventhub.notification.service;

/**
 * Une autre copie du meme evenement est en cours de traitement. Ce n'est pas une panne :
 * le message doit simplement etre represente plus tard, quand l'issue sera connue.
 */
public class EventInProgressException extends RuntimeException {

    public EventInProgressException(String eventId) {
        super("Evenement " + eventId + " deja en cours de traitement, nouvel essai plus tard");
    }
}
