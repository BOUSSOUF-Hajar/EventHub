package com.eventhub.booking.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Le client ne choisit que l'evenement et le nombre de places : son identite vient
 * du JWT, jamais du corps de la requete (sinon il pourrait reserver au nom d'autrui).
 */
public record CreateBookingRequest(
        @NotNull(message = "eventId est obligatoire") UUID eventId,
        @NotNull(message = "seatCount est obligatoire")
        @Min(value = 1, message = "il faut reserver au moins 1 place")
        @Max(value = 10, message = "10 places maximum par reservation") Integer seatCount) {
}
