package com.eventhub.event.repository;

import com.eventhub.event.domain.Event;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface EventRepository extends JpaRepository<Event, UUID> {

    /**
     * Retire des places restantes par un UPDATE atomique : deux confirmations
     * simultanees ne peuvent pas se marcher dessus, la ou "lire, soustraire, ecrire"
     * en perdrait une. La condition empeche le compteur de devenir negatif.
     *
     * @return 1 si les places ont ete retirees, 0 si l'evenement n'existe pas ou n'a
     *         plus assez de places
     */
    @Modifying
    @Query("""
            UPDATE Event e
            SET e.remainingSeats = e.remainingSeats - :seats
            WHERE e.id = :id AND e.remainingSeats >= :seats
            """)
    int decrementRemainingSeats(@Param("id") UUID id, @Param("seats") int seats);
}
