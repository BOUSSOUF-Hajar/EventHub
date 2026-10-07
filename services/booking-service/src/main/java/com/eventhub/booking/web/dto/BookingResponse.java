package com.eventhub.booking.web.dto;

import com.eventhub.booking.domain.Booking;
import com.eventhub.booking.domain.BookingStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Vue exposee au client. On ne renvoie pas l'entite JPA directement : cela evite
 * de figer le schema interne dans le contrat HTTP et de fuiter des champs.
 */
public record BookingResponse(
        UUID id,
        UUID eventId,
        Integer seatCount,
        BigDecimal totalAmount,
        BookingStatus status,
        Instant createdAt,
        Instant updatedAt) {

    public static BookingResponse from(Booking booking) {
        return new BookingResponse(
                booking.getId(),
                booking.getEventId(),
                booking.getSeatCount(),
                booking.getTotalAmount(),
                booking.getStatus(),
                booking.getCreatedAt(),
                booking.getUpdatedAt());
    }
}
