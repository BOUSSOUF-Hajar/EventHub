package com.eventhub.booking.web.error;

import java.util.UUID;

public class BookingNotFoundException extends RuntimeException {

    public BookingNotFoundException(UUID bookingId) {
        super("Reservation introuvable : " + bookingId);
    }
}
