package com.eventhub.event.service;

import com.eventhub.event.domain.ProcessedMessage;
import com.eventhub.event.repository.EventRepository;
import com.eventhub.event.repository.ProcessedMessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * EVT-5 : tient a jour les places restantes affichees dans le catalogue.
 *
 * Ce compteur est une <b>copie de lecture</b>, mise a jour de facon asynchrone. La
 * verite sur la disponibilite reste le verrou Redis de booking-service : le catalogue
 * peut afficher une place qu'un autre client vient de prendre, et la reservation sera
 * alors refusee par booking-service. C'est la coherence finale acceptee par le cahier
 * des charges.
 */
@Service
public class SeatAvailabilityService {

    private static final Logger log = LoggerFactory.getLogger(SeatAvailabilityService.class);

    private final EventRepository eventRepository;
    private final ProcessedMessageRepository processedMessageRepository;

    public SeatAvailabilityService(EventRepository eventRepository,
                                   ProcessedMessageRepository processedMessageRepository) {
        this.eventRepository = eventRepository;
        this.processedMessageRepository = processedMessageRepository;
    }

    /**
     * Retire les places d'une reservation confirmee, une seule fois par message.
     *
     * @return true si le message vient d'etre applique, false s'il l'avait deja ete
     */
    @Transactional
    public boolean applyConfirmedBooking(String messageId, UUID eventId, int seatCount) {
        if (processedMessageRepository.existsById(messageId)) {
            log.info("Message {} deja applique : ignore", messageId);
            return false;
        }
        // saveAndFlush : si une copie concurrente a deja insere la ligne, la violation de
        // cle primaire remonte ici et annule la transaction avant tout decompte.
        processedMessageRepository.saveAndFlush(new ProcessedMessage(messageId));

        if (eventRepository.decrementRemainingSeats(eventId, seatCount) == 0) {
            // Evenement supprime entre-temps, ou compteur deja a zero : rien a corriger
            // en relivrant, on trace et on considere le message comme traite.
            log.warn("Places restantes non mises a jour pour l'evenement {} ({} place(s)) : "
                    + "evenement introuvable ou compteur insuffisant", eventId, seatCount);
        } else {
            log.info("{} place(s) retiree(s) des places restantes de l'evenement {}", seatCount, eventId);
        }
        return true;
    }

    @Transactional(readOnly = true)
    public boolean isAlreadyApplied(String messageId) {
        return processedMessageRepository.existsById(messageId);
    }
}
