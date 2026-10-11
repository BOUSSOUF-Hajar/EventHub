package com.eventhub.notification.service;

import com.eventhub.notification.config.NotificationProperties;
import com.eventhub.notification.messaging.BookingEventMessage;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.stereotype.Component;

/**
 * Redige les emails a partir des seules donnees presentes dans l'evenement : le service
 * n'appelle personne pour les completer (pas d'appel REST entre services).
 */
@Component
public class EmailComposer {

    static final String REASON_PAYMENT_FAILED = "PAYMENT_FAILED";
    static final String REASON_PAYMENT_TIMEOUT = "PAYMENT_TIMEOUT";

    private final String from;

    public EmailComposer(NotificationProperties properties) {
        this.from = properties.from();
    }

    public SimpleMailMessage compose(NotificationType type, BookingEventMessage event) {
        SimpleMailMessage mail = new SimpleMailMessage();
        mail.setFrom(from);
        mail.setTo(event.customerEmail());
        switch (type) {
            case BOOKING_CONFIRMED -> {
                mail.setSubject("Votre réservation est confirmée");
                mail.setText(confirmedText(event));
            }
            case BOOKING_CANCELLED -> {
                mail.setSubject("Votre réservation a été annulée");
                mail.setText(cancelledText(event));
            }
        }
        return mail;
    }

    private String confirmedText(BookingEventMessage event) {
        StringBuilder text = new StringBuilder("Bonjour,\n\n")
                .append("Votre paiement a été accepté et votre réservation est confirmée.\n\n")
                .append("Référence : ").append(event.bookingId()).append('\n');
        if (event.seatCount() != null) {
            text.append("Nombre de places : ").append(event.seatCount()).append('\n');
        }
        if (event.amount() != null) {
            text.append("Montant payé : ").append(event.amount().toPlainString()).append(" EUR\n");
        }
        return text.append("\nÀ bientôt sur EventHub !\n").toString();
    }

    private String cancelledText(BookingEventMessage event) {
        return "Bonjour,\n\n"
                + "Votre réservation a été annulée : " + cancellationCause(event.reason()) + "\n"
                + "Aucun montant n'a été débité et les places ont été remises en vente.\n\n"
                + "Référence : " + event.bookingId() + "\n\n"
                + "Vous pouvez effectuer une nouvelle réservation sur EventHub.\n";
    }

    /** Un motif inconnu ne doit pas empecher de prevenir le client : on reste generique. */
    private String cancellationCause(String reason) {
        if (REASON_PAYMENT_FAILED.equals(reason)) {
            return "le paiement a été refusé.";
        }
        if (REASON_PAYMENT_TIMEOUT.equals(reason)) {
            return "le paiement n'a pas été finalisé dans le délai imparti.";
        }
        return "elle n'a pas pu être finalisée.";
    }
}
