package com.eventhub.booking.service;

import com.eventhub.booking.client.EventCatalogClient;
import com.eventhub.booking.client.EventSummary;
import com.eventhub.booking.config.EventHubRabbitProperties;
import com.eventhub.booking.domain.Booking;
import com.eventhub.booking.domain.BookingStatus;
import com.eventhub.booking.event.BookingRequestedEvent;
import com.eventhub.booking.repository.BookingRepository;
import com.eventhub.booking.web.error.BookingNotFoundException;
import com.eventhub.booking.web.error.NotEnoughSeatsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

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
     * Si l'etape 3 echoue (y compris au commit), les places verrouillees a l'etape 2
     * sont rendues : sans ce rattrapage, un echec base gelerait des places pour toujours.
     */
    public Booking create(UUID eventId, int seatCount, AuthenticatedCustomer customer) {
        EventSummary event = eventCatalogClient.findById(eventId);

        if (!seatLockService.tryLock(eventId, seatCount, event.totalCapacity())) {
            throw new NotEnoughSeatsException(eventId, seatCount);
        }

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
                return booking;
            });
        } catch (RuntimeException e) {
            seatLockService.release(eventId, seatCount);
            log.warn("Echec de la creation de reservation pour l'evenement {} : {} place(s) rendue(s)",
                    eventId, seatCount, e);
            throw e;
        }
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
     */
    @Transactional
    public void markAwaitingPayment(UUID bookingId) {
        bookingRepository.findById(bookingId).ifPresent(booking -> {
            if (booking.getStatus() == BookingStatus.PENDING) {
                booking.transitionTo(BookingStatus.AWAITING_PAYMENT);
            }
        });
    }
}
