package com.eventhub.booking.repository;

import com.eventhub.booking.domain.Booking;
import com.eventhub.booking.domain.BookingStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BookingRepository extends JpaRepository<Booking, UUID> {

    List<Booking> findByCustomerIdOrderByCreatedAtDesc(UUID customerId);

    List<Booking> findByStatusInAndCreatedAtBefore(List<BookingStatus> statuses, Instant threshold);

    /**
     * Lecture verrouillee (SELECT ... FOR UPDATE) pour les transitions de la Saga.
     *
     * Deux acteurs peuvent vouloir faire evoluer la meme reservation au meme instant : le
     * listener de paiement et le job d'expiration. Sans verrou, chacun lirait PENDING et
     * appliquerait sa transition, d'ou une reservation a la fois confirmee et liberee.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM Booking b WHERE b.id = :id")
    Optional<Booking> findByIdForUpdate(@Param("id") UUID id);

    /**
     * Passage PENDING -> AWAITING_PAYMENT sous forme d'UPDATE conditionnel.
     *
     * Le relais d'outbox appelle cette transition juste apres la publication, alors que
     * payment-service peut deja avoir repondu : charger l'entite puis l'ecrire risquerait
     * d'ecraser un CONFIRMED tout juste valide par un AWAITING_PAYMENT perime. La
     * condition sur le statut rend l'operation atomique et sans effet si la Saga a avance.
     */
    @Modifying
    @Query("""
            UPDATE Booking b
            SET b.status = com.eventhub.booking.domain.BookingStatus.AWAITING_PAYMENT, b.updatedAt = :now
            WHERE b.id = :id AND b.status = com.eventhub.booking.domain.BookingStatus.PENDING
            """)
    int markAwaitingPayment(@Param("id") UUID id, @Param("now") Instant now);
}
