package com.eventhub.booking.service;

import com.eventhub.booking.config.EventHubRabbitProperties;
import com.eventhub.booking.domain.Booking;
import com.eventhub.booking.domain.BookingStatus;
import com.eventhub.booking.event.BookingCancelledEvent;
import com.eventhub.booking.repository.BookingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

/**
 * Ces cas portent sur ce que RabbitMQ rend normal : doublons, messages en retard, et
 * pannes entre la base et Redis. Ils sont unitaires parce qu'un echec de commit ou une
 * panne Redis au mauvais moment se simule en une ligne ici, et tres mal sur de vrais
 * conteneurs.
 */
@ExtendWith(MockitoExtension.class)
class BookingSagaTest {

    private static final UUID BOOKING_ID = UUID.randomUUID();
    private static final UUID EVENT_ID = UUID.randomUUID();

    @Mock
    private BookingRepository bookingRepository;
    @Mock
    private SeatLockService seatLockService;
    @Mock
    private OutboxWriter outboxWriter;
    @Mock
    private TransactionTemplate transactionTemplate;

    private BookingSaga saga;

    @BeforeEach
    void setUp() {
        EventHubRabbitProperties rabbitProperties = new EventHubRabbitProperties(
                "booking.events",
                new EventHubRabbitProperties.RoutingKeys("booking.requested", "booking.confirmed", "booking.cancelled"),
                new EventHubRabbitProperties.Payment("payment.events",
                        new EventHubRabbitProperties.PaymentRoutingKeys("payment.succeeded", "payment.failed")));

        saga = new BookingSaga(bookingRepository, seatLockService, outboxWriter, transactionTemplate, rabbitProperties);

        // Execute le callback transactionnel sur place, sans transaction reelle.
        lenient().doAnswer(call -> {
            TransactionCallback<?> callback = call.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactionTemplate).execute(any());
    }

    @Test
    @DisplayName("BKG-4 : payment.succeeded confirme la reservation et garde ses places")
    void confirmMovesTheBookingToConfirmed() {
        Booking booking = givenBooking(BookingStatus.AWAITING_PAYMENT);

        assertThat(saga.confirm(BOOKING_ID)).isTrue();

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        then(outboxWriter).should().append(eq("booking"), any(), eq("booking.confirmed"), any());
        then(seatLockService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("le resultat peut arriver avant que le relais ait marque AWAITING_PAYMENT")
    void confirmWorksOnAStillPendingBooking() {
        Booking booking = givenBooking(BookingStatus.PENDING);

        assertThat(saga.confirm(BOOKING_ID)).isTrue();

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
    }

    @Test
    @DisplayName("un payment.succeeded relivre ne produit pas un second booking.confirmed")
    void duplicateConfirmationIsANoOp() {
        givenBooking(BookingStatus.CONFIRMED);

        assertThat(saga.confirm(BOOKING_ID)).isFalse();

        then(outboxWriter).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("un paiement arrive apres expiration ne ressuscite pas la reservation")
    void latePaymentDoesNotReviveAnExpiredBooking() {
        Booking booking = givenBooking(BookingStatus.CANCELLED);

        assertThat(saga.confirm(BOOKING_ID)).isFalse();

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        then(outboxWriter).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("BKG-5 : payment.failed annule la reservation et rend ses places")
    void cancelReleasesTheSeats() {
        Booking booking = givenBooking(BookingStatus.AWAITING_PAYMENT);

        assertThat(saga.cancel(BOOKING_ID, BookingSaga.REASON_PAYMENT_FAILED)).isTrue();

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        then(seatLockService).should().release(EVENT_ID, 2);

        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        then(outboxWriter).should().append(eq("booking"), any(), eq("booking.cancelled"), event.capture());
        assertThat(event.getValue()).isInstanceOfSatisfying(BookingCancelledEvent.class, cancelled -> {
            assertThat(cancelled.reason()).isEqualTo("PAYMENT_FAILED");
            assertThat(cancelled.seatCount()).isEqualTo(2);
            assertThat(cancelled.customerEmail()).isEqualTo("alice@example.com");
        });
    }

    @Test
    @DisplayName("les places ne sont rendues qu'une fois la transaction terminee")
    void seatsAreReleasedOnlyAfterTheTransaction() {
        givenBooking(BookingStatus.AWAITING_PAYMENT);

        saga.cancel(BOOKING_ID, BookingSaga.REASON_PAYMENT_FAILED);

        InOrder order = inOrder(transactionTemplate, seatLockService);
        order.verify(transactionTemplate).execute(any());
        order.verify(seatLockService).release(EVENT_ID, 2);
    }

    @Test
    @DisplayName("si le commit de l'annulation echoue, aucune place n'est rendue : le message sera relivre")
    void failedCommitReleasesNothing() {
        givenBooking(BookingStatus.AWAITING_PAYMENT);
        willAnswer(call -> {
            TransactionCallback<?> callback = call.getArgument(0);
            callback.doInTransaction(mock(TransactionStatus.class));
            throw new TransactionSystemException("connexion perdue pendant le commit");
        }).given(transactionTemplate).execute(any());

        assertThatThrownBy(() -> saga.cancel(BOOKING_ID, BookingSaga.REASON_PAYMENT_FAILED))
                .isInstanceOf(TransactionSystemException.class);

        // rendre ici puis de nouveau a la relivraison ferait vendre deux fois les memes places
        then(seatLockService).should(never()).release(any(UUID.class), anyInt());
    }

    @Test
    @DisplayName("un payment.failed relivre ne rend pas les places une seconde fois")
    void duplicateCancellationDoesNotReleaseTwice() {
        givenBooking(BookingStatus.CANCELLED);

        assertThat(saga.cancel(BOOKING_ID, BookingSaga.REASON_PAYMENT_FAILED)).isFalse();

        then(seatLockService).shouldHaveNoInteractions();
        then(outboxWriter).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("un payment.failed en retard n'annule pas une reservation deja confirmee")
    void lateFailureDoesNotCancelAConfirmedBooking() {
        Booking booking = givenBooking(BookingStatus.CONFIRMED);

        assertThat(saga.cancel(BOOKING_ID, BookingSaga.REASON_PAYMENT_FAILED)).isFalse();

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        then(seatLockService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("une panne Redis a la liberation ne fait pas echouer l'annulation deja validee")
    void redisFailureOnReleaseDoesNotFailTheCancellation() {
        Booking booking = givenBooking(BookingStatus.AWAITING_PAYMENT);
        willThrow(new DataAccessResourceFailureException("redis injoignable"))
                .given(seatLockService).release(EVENT_ID, 2);

        // Lever ferait relivrer le message, pour rien : la reservation est deja CANCELLED.
        assertThat(saga.cancel(BOOKING_ID, BookingSaga.REASON_PAYMENT_FAILED)).isTrue();
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
    }

    @Test
    @DisplayName("un resultat de paiement pour une reservation inconnue est ignore sans erreur")
    void unknownBookingIsIgnored() {
        given(bookingRepository.findByIdForUpdate(BOOKING_ID)).willReturn(Optional.empty());

        assertThat(saga.confirm(BOOKING_ID)).isFalse();
        assertThat(saga.cancel(BOOKING_ID, BookingSaga.REASON_PAYMENT_FAILED)).isFalse();

        then(outboxWriter).shouldHaveNoInteractions();
        then(seatLockService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("BKG-3 : un echec sur une reservation n'empeche pas d'expirer les suivantes")
    void expirationContinuesAfterAFailure() {
        Booking healthy = booking(BookingStatus.AWAITING_PAYMENT);
        UUID brokenId = UUID.randomUUID();
        UUID healthyId = UUID.randomUUID();
        Booking brokenCandidate = mock(Booking.class);
        Booking healthyCandidate = mock(Booking.class);
        given(brokenCandidate.getId()).willReturn(brokenId);
        given(healthyCandidate.getId()).willReturn(healthyId);
        given(bookingRepository.findByStatusInAndCreatedAtBefore(anyList(), any(Instant.class)))
                .willReturn(List.of(brokenCandidate, healthyCandidate));
        given(bookingRepository.findByIdForUpdate(brokenId))
                .willThrow(new DataAccessResourceFailureException("base injoignable"));
        given(bookingRepository.findByIdForUpdate(healthyId)).willReturn(Optional.of(healthy));

        assertThat(saga.expireUnpaid(Instant.now())).isEqualTo(1);

        assertThat(healthy.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        then(outboxWriter).should().append(eq("booking"), any(), eq("booking.cancelled"), event.capture());
        assertThat(((BookingCancelledEvent) event.getValue()).reason()).isEqualTo("PAYMENT_TIMEOUT");
    }

    private Booking givenBooking(BookingStatus status) {
        Booking booking = booking(status);
        given(bookingRepository.findByIdForUpdate(BOOKING_ID)).willReturn(Optional.of(booking));
        return booking;
    }

    private Booking booking(BookingStatus status) {
        Booking booking = new Booking(EVENT_ID, UUID.randomUUID(), "alice@example.com", 2, new BigDecimal("50.00"));
        if (status != BookingStatus.PENDING) {
            booking.transitionTo(status);
        }
        return booking;
    }
}
