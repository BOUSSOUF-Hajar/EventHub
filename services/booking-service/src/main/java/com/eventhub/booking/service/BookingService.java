package com.eventhub.booking.service;

import com.eventhub.booking.client.EventCatalogClient;
import com.eventhub.booking.client.EventSummary;
import com.eventhub.booking.config.EventHubRabbitProperties;
import com.eventhub.booking.domain.Booking;
import com.eventhub.booking.event.BookingRequestedEvent;
import com.eventhub.booking.repository.BookingRepository;
import com.eventhub.booking.web.error.BookingNotFoundException;
import com.eventhub.booking.web.error.EventNotBookableException;
import com.eventhub.booking.web.error.NotEnoughSeatsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class BookingService {

    private static final Logger log = LoggerFactory.getLogger(BookingService.class);
    private static final String AGGREGATE_TYPE = "booking";

    private final BookingRepository bookingRepository;
    private final EventCatalogClient eventCatalogClient;
    private final SeatLockService seatLockService;
    private final OutboxWriter outboxWriter;
    private final TransactionTemplate transactionTemplate;
    private final EventHubRabbitProperties rabbitProperties;

    public BookingService(BookingRepository bookingRepository,
                          EventCatalogClient eventCatalogClient,
                          SeatLockService seatLockService,
                          OutboxWriter outboxWriter,
                          TransactionTemplate transactionTemplate,
                          EventHubRabbitProperties rabbitProperties) {
        this.bookingRepository = bookingRepository;
        this.eventCatalogClient = eventCatalogClient;
        this.seatLockService = seatLockService;
        this.outboxWriter = outboxWriter;
        this.transactionTemplate = transactionTemplate;
        this.rabbitProperties = rabbitProperties;
    }

    /**
     * Cree une reservation (BKG-1) en respectant la disponibilite (BKG-2).
     *
     * Ordre des operations, et pourquoi :
     * <ol>
     *   <li>lecture de la capacite aupres d'event-service, <b>hors transaction</b> :
     *       on ne garde pas une connexion base ouverte pendant un appel reseau ;</li>
     *   <li>verrou Redis atomique : c'est le point de serialisation qui empeche
     *       la sur-reservation entre instances concurrentes ;</li>
     *   <li>persistance de la reservation + ligne d'outbox dans une seule transaction.</li>
     * </ol>
     *
     * Si l'etape 3 echoue, les places verrouillees a l'etape 2 sont rendues : sans ce
     * rattrapage, un echec base gelerait des places pour toujours. Voir
     * {@link #recoverOrRelease} pour le cas d'un commit a l'issue incertaine.
     */
    public Booking create(UUID eventId, int seatCount, AuthenticatedCustomer customer) {
        EventSummary event = eventCatalogClient.findById(eventId);
        requireBookable(event, eventId);

        if (!seatLockService.tryLock(eventId, seatCount, event.totalCapacity())) {
            throw new NotEnoughSeatsException(eventId, seatCount);
        }

        // Renseigne a la fin du callback : s'il est rempli quand une exception remonte,
        // c'est que l'echec vient du commit et non du code metier.
        AtomicReference<Booking> persisted = new AtomicReference<>();
        try {
            return transactionTemplate.execute(status -> {
                Booking booking = bookingRepository.save(new Booking(
                        eventId, customer.id(), customer.email(), seatCount, event.priceFor(seatCount)));

                outboxWriter.append(AGGREGATE_TYPE, booking.getId(),
                        rabbitProperties.routingKey().requested(),
                        new BookingRequestedEvent(
                                booking.getId(),
                                booking.getEventId(),
                                booking.getCustomerId(),
                                booking.getCustomerEmail(),
                                booking.getSeatCount(),
                                booking.getTotalAmount()));

                log.info("Reservation {} creee ({} place(s), evenement {})",
                        booking.getId(), seatCount, eventId);
                persisted.set(booking);
                return booking;
            });
        } catch (RuntimeException e) {
            return recoverOrRelease(persisted.get(), eventId, seatCount, e);
        }
    }

    /** Un evenement passe ou sans prix renseigne ne se reserve pas : on refuse avant de verrouiller. */
    private void requireBookable(EventSummary event, UUID eventId) {
        if (!event.startsAt().isAfter(Instant.now())) {
            throw new EventNotBookableException(eventId, "il a déjà commencé");
        }
        if (event.unitPrice().signum() <= 0) {
            throw new EventNotBookableException(eventId, "son prix n'est pas renseigné");
        }
    }

    /**
     * Decide du sort des places apres un echec de la transaction.
     *
     * Un echec au commit ne dit pas si la base a valide ou non (connexion coupee apres
     * l'envoi du COMMIT) : on relit donc la reservation. Si elle existe, elle est valide
     * et garde ses places. Si on ne peut pas le savoir, on garde aussi les places :
     * des places bloquees a tort se corrigent, une sur-reservation non.
     */
    private Booking recoverOrRelease(Booking persisted, UUID eventId, int seatCount, RuntimeException failure) {
        if (persisted != null) {
            boolean committed;
            try {
                committed = bookingRepository.existsById(persisted.getId());
            } catch (RuntimeException checkFailure) {
                failure.addSuppressed(checkFailure);
                log.error("Issue du commit inconnue pour la reservation {} (evenement {}) : "
                        + "{} place(s) conservee(s), a reconcilier", persisted.getId(), eventId, seatCount, failure);
                throw failure;
            }
            if (committed) {
                log.warn("Reservation {} validee malgre une erreur au commit", persisted.getId(), failure);
                return persisted;
            }
        }

        try {
            seatLockService.release(eventId, seatCount);
            log.warn("Echec de la creation de reservation pour l'evenement {} : {} place(s) rendue(s)",
                    eventId, seatCount, failure);
        } catch (RuntimeException releaseFailure) {
            // L'erreur d'origine reste celle que voit l'appelant.
            failure.addSuppressed(releaseFailure);
            log.error("Echec de la creation de reservation pour l'evenement {} ET de la liberation "
                    + "de {} place(s) : compteur Redis a reconcilier", eventId, seatCount, failure);
        }
        throw failure;
    }

    @Transactional(readOnly = true)
    public List<Booking> findMine(UUID customerId) {
        return bookingRepository.findByCustomerIdOrderByCreatedAtDesc(customerId);
    }

    /**
     * BKG-6 : un client ne peut consulter que ses propres reservations.
     * Le controle se fait ici, sur l'identite portee par le JWT, et pas sur un
     * parametre de requete que l'appelant pourrait falsifier.
     */
    @Transactional(readOnly = true)
    public Booking findOwned(UUID bookingId, UUID customerId) {
        return bookingRepository.findById(bookingId)
                .filter(booking -> booking.getCustomerId().equals(customerId))
                .orElseThrow(() -> new BookingNotFoundException(bookingId));
    }

    /**
     * Appele par le relais d'outbox une fois "booking.requested" reellement publie :
     * tant que le message n'est pas parti, la reservation n'attend pas encore de paiement.
     * Sans effet si le resultat du paiement est deja arrive entre-temps.
     */
    @Transactional
    public void markAwaitingPayment(UUID bookingId) {
        bookingRepository.markAwaitingPayment(bookingId, Instant.now());
    }
}
