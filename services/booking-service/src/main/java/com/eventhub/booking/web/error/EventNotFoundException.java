package com.eventhub.booking.web.error;

import java.util.UUID;

public class EventNotFoundException extends RuntimeException {

    public EventNotFoundException(UUID eventId) {
        super("Evenement introuvable : " + eventId);
    }
}
