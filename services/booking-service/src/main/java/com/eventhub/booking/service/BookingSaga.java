package com.eventhub.booking.service;

import com.eventhub.booking.config.EventHubRabbitProperties;
import com.eventhub.booking.domain.Booking;
import com.eventhub.booking.domain.BookingStatus;
import com.eventhub.booking.event.BookingCancelledEvent;
import com.eventhub.booking.event.BookingConfirmedEvent;
import com.eventhub.booking.repository.BookingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Etapes finales de la Saga "reservation" : confirmation, ou compensation.
 *
 * Toutes les methodes sont <b>idempotentes</b> et tolerent le desordre : RabbitMQ peut
 * livrer un message deux fois, et le job d'expiration peut croiser un resultat de
 * paiement. C'est la machine a etats de {@link Booking}, lue sous verrou, qui arbitre :
 * une reservation deja dans un etat terminal ne bouge plus.
 *
 * Aucune de ces methodes ne leve pour un message "en retard" ou orphelin : lever ferait
 * relivrer indefiniment un message qui ne pourra jamais etre applique.
 */
@Service
public class BookingSaga {

    public static final String REASON_PAYMENT_FAILED = "PAYMENT_FAILED";
    public static final String REASON_PAYMENT_TIMEOUT = "PAYMENT_TIMEOUT";

    private static final Logger log = LoggerFactory.getLogger(BookingSaga.class);
    private static final String AGGREGATE_TYPE = "booking";
    private static final List<BookingStatus> UNPAID = List.of(BookingStatus.PENDING, BookingStatus.AWAITING_PAYMENT);

    private final BookingRepository bookingRepository;
    private final SeatLockService seatLockService;
    private final OutboxWriter outboxWriter;
    private final TransactionTemplate transactionTemplate;
    private final EventHubRabbitProperties rabbitProperties;

    public BookingSaga(BookingRepository bookingRepository,
                       SeatLockService seatLockService,
                       OutboxWriter outboxWriter,
                       TransactionTemplate transactionTemplate,
                       EventHubRabbitProperties rabbitProperties) {
        this.bookingRepository = bookingRepository;
        this.seatLockService = seatLockService;
        this.outboxWriter = outboxWriter;
        this.transactionTemplate = transactionTemplate;
        this.rabbitProperties = rabbitProperties;
    }

    /**
     * BKG-4 : le paiement a reussi, la reservation devient definitive. Les places restent
     * comptees dans Redis : elles sont desormais vendues.
     *
     * @return true si la reservation vient d'etre confirmee, false si le message etait
     *         un doublon ou n'etait plus applicable
     */
    public boolean confirm(UUID bookingId) {
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            Booking booking = bookingRepository.findByIdForUpdate(bookingId).orElse(null);
            if (booking == null) {
                log.warn("payment.succeeded recu pour une reservation inconnue {} : ignore", bookingId);
                return false;
            }
            if (booking.getStatus() == BookingStatus.CANCELLED) {
                // La reservation a expire avant l'arrivee du paiement : les places sont
                // deja rendues, peut-etre revendues. On ne la ressuscite pas.
                log.error("Paiement accepte pour la reservation {} deja annulee : remboursement a declencher",
                        bookingId);
                return false;
            }
            if (!booking.transitionTo(BookingStatus.CONFIRMED)) {
                return false; // doublon
            }

            outboxWriter.append(AGGREGATE_TYPE, booking.getId(),
                    rabbitProperties.routingKey().confirmed(),
                    new BookingConfirmedEvent(
                            booking.getId(),
                            booking.getEventId(),
                            booking.getCustomerId(),
                            booking.getCustomerEmail(),
                            booking.getSeatCount(),
                            booking.getTotalAmount()));

            log.info("Reservation {} confirmee", bookingId);
            return true;
        }));
    }

    /**
     * BKG-5 : action compensatoire. Annule la reservation puis rend ses places.
     *
     * Les places sont rendues <b>apres</b> le commit, jamais avant. Redis n'etant pas dans
     * la transaction, les deux ordres ont une faille, mais pas de meme gravite :
     * <ul>
     *   <li>rendre avant le commit : si le commit echoue, le message est relivre et les
     *       places sont rendues une seconde fois. Le compteur passe sous la realite, on
     *       vend plus de places qu'il n'y en a ;</li>
     *   <li>rendre apres le commit : si la liberation echoue, des places restent bloquees
     *       a tort. C'est signale en erreur et rattrapable, sans sur-reservation.</li>
     * </ul>
     * Le statut CANCELLED garantit par ailleurs qu'une relivraison ne libere rien de plus.
     *
     * @return true si la reservation vient d'etre annulee
     */
    public boolean cancel(UUID bookingId, String reason) {
        Booking cancelled = transactionTemplate.execute(status -> {
            Booking booking = bookingRepository.findByIdForUpdate(bookingId).orElse(null);
            if (booking == null) {
                log.warn("Annulation ({}) demandee pour une reservation inconnue {} : ignoree", reason, bookingId);
                return null;
            }
            if (booking.getStatus() == BookingStatus.CONFIRMED) {
                log.warn("Annulation ({}) ignoree : la reservation {} est deja confirmee", reason, bookingId);
                return null;
            }
            if (!booking.transitionTo(BookingStatus.CANCELLED)) {
                return null; // doublon
            }

            outboxWriter.append(AGGREGATE_TYPE, booking.getId(),
                    rabbitProperties.routingKey().cancelled(),
                    new BookingCancelledEvent(
                            booking.getId(),
                            booking.getEventId(),
                            booking.getCustomerId(),
                            booking.getCustomerEmail(),
                            booking.getSeatCount(),
                            reason));
            return booking;
        });

        if (cancelled == null) {
            return false;
        }
        releaseSeats(cancelled, reason);
        return true;
    }

    /**
     * BKG-3 : annule les reservations restees impayees depuis trop longtemps.
     *
     * Chaque reservation est traitee dans sa propre transaction : un echec sur l'une
     * n'empeche pas d'expirer les autres, et la relecture sous verrou dans
     * {@link #cancel} ecarte celles qui auraient ete payees entre-temps.
     *
     * @return le nombre de reservations effectivement annulees
     */
    public int expireUnpaid(Instant createdBefore) {
        int expired = 0;
        for (Booking candidate : bookingRepository.findByStatusInAndCreatedAtBefore(UNPAID, createdBefore)) {
            try {
                if (cancel(candidate.getId(), REASON_PAYMENT_TIMEOUT)) {
                    expired++;
                }
            } catch (RuntimeException e) {
                log.error("Expiration impossible pour la reservation {}, nouvel essai au prochain passage",
                        candidate.getId(), e);
            }
        }
        return expired;
    }

    private void releaseSeats(Booking booking, String reason) {
        try {
            seatLockService.release(booking.getEventId(), booking.getSeatCount());
            log.info("Reservation {} annulee ({}) : {} place(s) rendue(s) a l'evenement {}",
                    booking.getId(), reason, booking.getSeatCount(), booking.getEventId());
        } catch (RuntimeException e) {
            // On ne releve pas : la reservation est annulee en base, relivrer le message
            // n'y changerait rien (elle est deja CANCELLED, donc ignoree).
            log.error("Reservation {} annulee ({}) mais {} place(s) NON rendue(s) a l'evenement {} : "
                            + "compteur Redis a reconcilier",
                    booking.getId(), reason, booking.getSeatCount(), booking.getEventId(), e);
        }
    }
}
