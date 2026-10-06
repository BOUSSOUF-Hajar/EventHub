package com.eventhub.booking.web.error;

import java.util.UUID;

public class EventCatalogUnavailableException extends RuntimeException {

    public EventCatalogUnavailableException(UUID eventId, Throwable cause) {
        super("Le service Evenements est injoignable (evenement " + eventId + ")", cause);
    }
}
