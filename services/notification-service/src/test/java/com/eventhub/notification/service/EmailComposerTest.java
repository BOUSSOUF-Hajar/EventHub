package com.eventhub.notification.service;

import com.eventhub.notification.config.NotificationProperties;
import com.eventhub.notification.messaging.BookingEventMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mail.SimpleMailMessage;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EmailComposerTest {

    private static final UUID BOOKING_ID = UUID.randomUUID();

    private final EmailComposer composer = new EmailComposer(new NotificationProperties("no-reply@eventhub.test",
            new NotificationProperties.Deduplication(Duration.ofSeconds(30), Duration.ofDays(7))));

    @Test
    @DisplayName("confirmation : adressee au client, avec reference, places et montant")
    void confirmationEmailCarriesTheBookingDetails() {
        SimpleMailMessage mail = composer.compose(NotificationType.BOOKING_CONFIRMED,
                new BookingEventMessage("evt-1", BOOKING_ID, "alice@example.com", 3, new BigDecimal("75.00"), null));

        assertThat(mail.getFrom()).isEqualTo("no-reply@eventhub.test");
        assertThat(mail.getTo()).containsExactly("alice@example.com");
        assertThat(mail.getSubject()).isEqualTo("Votre réservation est confirmée");
        assertThat(mail.getText())
                .contains("Référence : " + BOOKING_ID)
                .contains("Nombre de places : 3")
                .contains("Montant payé : 75.00 EUR");
    }

    @Test
    @DisplayName("confirmation sans montant ni places : l'email part quand meme, sans ligne vide de sens")
    void confirmationEmailToleratesMissingOptionalFields() {
        SimpleMailMessage mail = composer.compose(NotificationType.BOOKING_CONFIRMED,
                new BookingEventMessage("evt-1", BOOKING_ID, "alice@example.com", null, null, null));

        assertThat(mail.getText())
                .contains("Référence : " + BOOKING_ID)
                .doesNotContain("null")
                .doesNotContain("Montant")
                .doesNotContain("Nombre de places");
    }

    @ParameterizedTest
    // quoteCharacter : l'apostrophe, guillemet par defaut de @CsvSource, figure dans les textes
    @CsvSource(nullValues = "NULL", delimiter = '|', quoteCharacter = '"', value = {
            "PAYMENT_FAILED  | le paiement a été refusé.",
            "PAYMENT_TIMEOUT | le paiement n'a pas été finalisé dans le délai imparti.",
            "MOTIF_FUTUR     | elle n'a pas pu être finalisée.",
            "NULL            | elle n'a pas pu être finalisée."
    })
    @DisplayName("annulation : le motif est traduit, un motif inconnu reste comprehensible")
    void cancellationEmailExplainsTheReason(String reason, String expectedCause) {
        SimpleMailMessage mail = composer.compose(NotificationType.BOOKING_CANCELLED,
                new BookingEventMessage("evt-1", BOOKING_ID, "alice@example.com", 2, null, reason));

        assertThat(mail.getSubject()).isEqualTo("Votre réservation a été annulée");
        assertThat(mail.getText())
                .contains("Votre réservation a été annulée : " + expectedCause)
                .contains("Référence : " + BOOKING_ID)
                .doesNotContain("null");
    }
}
