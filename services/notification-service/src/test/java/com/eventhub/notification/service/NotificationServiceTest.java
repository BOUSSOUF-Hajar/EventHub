package com.eventhub.notification.service;

import com.eventhub.notification.config.NotificationProperties;
import com.eventhub.notification.messaging.BookingEventMessage;
import com.eventhub.notification.service.ProcessedEventStore.Claim;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;

/**
 * Ces cas portent sur les pannes entre l'envoi de l'email et Redis : faciles a provoquer
 * avec des doubles de test, tres difficiles a declencher au bon instant sur de vrais
 * conteneurs.
 */
@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    private static final String EVENT_ID = "evt-1";

    @Mock
    private ProcessedEventStore processedEvents;
    @Mock
    private JavaMailSender mailSender;

    private NotificationService notificationService;

    private final BookingEventMessage event = new BookingEventMessage(
            EVENT_ID, UUID.randomUUID(), "alice@example.com", 2, new BigDecimal("50.00"), null);

    @BeforeEach
    void setUp() {
        EmailComposer composer = new EmailComposer(new NotificationProperties("no-reply@eventhub.test",
                new NotificationProperties.Deduplication(Duration.ofSeconds(30), Duration.ofDays(7))));
        notificationService = new NotificationService(processedEvents, composer, mailSender);
    }

    @Test
    @DisplayName("evenement nouveau : reserve, envoie l'email au client, puis marque traite")
    void newEventIsClaimedSentThenMarkedDone() {
        given(processedEvents.claim(EVENT_ID)).willReturn(Claim.ACQUIRED);

        assertThat(notificationService.notify(NotificationType.BOOKING_CONFIRMED, event)).isTrue();

        ArgumentCaptor<SimpleMailMessage> mail = ArgumentCaptor.forClass(SimpleMailMessage.class);
        InOrder order = inOrder(processedEvents, mailSender);
        order.verify(processedEvents).claim(EVENT_ID);
        order.verify(mailSender).send(mail.capture());
        order.verify(processedEvents).markDone(EVENT_ID);
        assertThat(mail.getValue().getTo()).containsExactly("alice@example.com");
    }

    @Test
    @DisplayName("NTF-3 : evenement deja notifie, aucun email n'est renvoye")
    void duplicateEventSendsNothing() {
        given(processedEvents.claim(EVENT_ID)).willReturn(Claim.DUPLICATE);

        assertThat(notificationService.notify(NotificationType.BOOKING_CONFIRMED, event)).isFalse();

        then(mailSender).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("copie concurrente en cours : pas d'email, le message devra etre represente")
    void concurrentCopyIsDeferred() {
        given(processedEvents.claim(EVENT_ID)).willReturn(Claim.IN_PROGRESS);

        assertThatThrownBy(() -> notificationService.notify(NotificationType.BOOKING_CONFIRMED, event))
                .isInstanceOf(EventInProgressException.class);

        then(mailSender).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("echec SMTP : la reservation est rendue et l'erreur remonte pour un nouvel essai")
    void smtpFailureReleasesTheClaim() {
        given(processedEvents.claim(EVENT_ID)).willReturn(Claim.ACQUIRED);
        willThrow(new MailSendException("smtp injoignable")).given(mailSender).send(any(SimpleMailMessage.class));

        assertThatThrownBy(() -> notificationService.notify(NotificationType.BOOKING_CONFIRMED, event))
                .isInstanceOf(MailSendException.class);

        then(processedEvents).should().release(EVENT_ID);
        // marquer traite ici ferait perdre l'email definitivement
        then(processedEvents).should(never()).markDone(EVENT_ID);
    }

    @Test
    @DisplayName("email parti mais marquage impossible : on ne leve pas, sinon l'email serait renvoye")
    void failureToMarkDoneDoesNotTriggerAResend() {
        given(processedEvents.claim(EVENT_ID)).willReturn(Claim.ACQUIRED);
        willThrow(new DataAccessResourceFailureException("redis injoignable"))
                .given(processedEvents).markDone(EVENT_ID);

        assertThat(notificationService.notify(NotificationType.BOOKING_CONFIRMED, event)).isTrue();

        then(processedEvents).should(never()).release(EVENT_ID);
    }

    @Test
    @DisplayName("si rendre la reservation echoue aussi, l'appelant voit l'erreur d'envoi")
    void failedReleaseDoesNotHideTheSendFailure() {
        given(processedEvents.claim(EVENT_ID)).willReturn(Claim.ACQUIRED);
        willThrow(new MailSendException("smtp injoignable")).given(mailSender).send(any(SimpleMailMessage.class));
        willThrow(new DataAccessResourceFailureException("redis injoignable"))
                .given(processedEvents).release(EVENT_ID);

        assertThatThrownBy(() -> notificationService.notify(NotificationType.BOOKING_CONFIRMED, event))
                .isInstanceOf(MailSendException.class)
                .hasSuppressedException(new DataAccessResourceFailureException("redis injoignable"));
    }
}
