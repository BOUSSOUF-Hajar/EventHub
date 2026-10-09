package com.eventhub.notification.service;

import com.eventhub.notification.messaging.BookingEventMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final ProcessedEventStore processedEvents;
    private final EmailComposer emailComposer;
    private final JavaMailSender mailSender;

    public NotificationService(ProcessedEventStore processedEvents,
                               EmailComposer emailComposer,
                               JavaMailSender mailSender) {
        this.processedEvents = processedEvents;
        this.emailComposer = emailComposer;
        this.mailSender = mailSender;
    }

    /**
     * Envoie la notification d'un evenement, une seule fois (NTF-3).
     *
     * @return true si un email vient d'etre envoye, false si l'evenement etait un doublon
     * @throws EventInProgressException si une autre copie de l'evenement est en cours de
     *                                  traitement : l'appelant doit reessayer plus tard
     */
    public boolean notify(NotificationType type, BookingEventMessage event) {
        String eventId = event.messageId();

        switch (processedEvents.claim(eventId)) {
            case DUPLICATE -> {
                log.info("Evenement {} deja notifie ({}, reservation {}) : ignore",
                        eventId, type, event.bookingId());
                return false;
            }
            case IN_PROGRESS -> throw new EventInProgressException(eventId);
            case ACQUIRED -> {
                // suite ci-dessous
            }
        }

        try {
            mailSender.send(emailComposer.compose(type, event));
        } catch (RuntimeException e) {
            // On rend la reservation : sans cela, le prochain essai attendrait la fin
            // du bail pour rien.
            releaseQuietly(eventId, e);
            throw e;
        }

        try {
            processedEvents.markDone(eventId);
        } catch (RuntimeException e) {
            // L'email est parti : lever ferait retenter l'envoi, donc un doublon certain.
            // On acquitte ; le bail expirera de lui-meme.
            log.error("Email {} envoye pour la reservation {} mais evenement {} non marque comme traite",
                    type, event.bookingId(), eventId, e);
        }

        log.info("Email {} envoye a {} pour la reservation {}", type, event.customerEmail(), event.bookingId());
        return true;
    }

    private void releaseQuietly(String eventId, RuntimeException sendFailure) {
        try {
            processedEvents.release(eventId);
        } catch (RuntimeException releaseFailure) {
            // L'erreur d'envoi reste celle que voit l'appelant.
            sendFailure.addSuppressed(releaseFailure);
        }
    }
}
