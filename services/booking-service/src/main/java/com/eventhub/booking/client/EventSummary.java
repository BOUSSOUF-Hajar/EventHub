package com.eventhub.booking.client;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Vue minimale d'un evenement telle que booking-service en a besoin.
 * On ne deserialise volontairement que les champs utiles : le contrat reste
 * tolerant aux ajouts de champs cote event-service.
 *
 * La validation des champs obligatoires est faite par {@link EventCatalogClient} :
 * ce record ne fait aucune correction silencieuse (un prix absent ne devient pas zero).
 */
public record EventSummary(UUID id, Integer totalCapacity, BigDecimal unitPrice, Instant startsAt) {

    public BigDecimal priceFor(int seatCount) {
        return unitPrice.multiply(BigDecimal.valueOf(seatCount));
    }
}
