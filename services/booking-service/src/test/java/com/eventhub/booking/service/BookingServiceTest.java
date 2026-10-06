package com.eventhub.booking.service;

import com.eventhub.booking.client.EventCatalogClient;
import com.eventhub.booking.client.EventSummary;
import com.eventhub.booking.config.EventHubRabbitProperties;
import com.eventhub.booking.domain.Booking;
import com.eventhub.booking.repository.BookingRepository;
import com.eventhub.booking.web.error.EventNotBookableException;
import com.eventhub.booking.web.error.NotEnoughSeatsException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

/**
 * Ces cas portent sur l'enchainement "verrou -> persistance -> compensation".
 * Ils sont volontairement unitaires : simuler un echec de commit est trivial avec
 * un double de test, et couteux a provoquer sur une vraie base.
 */
@ExtendWith(MockitoExtension.class)
class BookingServiceTest {

    private static final UUID EVENT_ID = UUID.randomUUID();

    @Mock
    private BookingRepository bookingRepository;
    @Mock
    private EventCatalogClient eventCatalogClient;
    @Mock
    private SeatLockService seatLockService;
    @Mock
    private OutboxWriter outboxWriter;
    @Mock
    private TransactionTemplate transactionTemplate;

    private BookingService bookingService;

    private final AuthenticatedCustomer alice =
            new AuthenticatedCustomer(UUID.randomUUID(), "alice@example.com");

    @BeforeEach
    void setUp() {
        EventHubRabbitProperties rabbitProperties = new EventHubRabbitProperties(
                "booking.events",
                new EventHubRabbitProperties.RoutingKeys("booking.requested", "booking.confirmed", "booking.cancelled"));

        bookingService = new BookingService(bookingRepository, eventCatalogClient, seatLockService,
                outboxWriter, transactionTemplate, rabbitProperties);
    }

    @Test
    @DisplayName("faute de places, rien n'est persiste et aucun evenement n'est produit")
    void refusedLockStopsEverything() {
        given(eventCatalogClient.findById(EVENT_ID)).willReturn(summary(10));
        given(seatLockService.tryLock(EVENT_ID, 4, 10)).willReturn(false);

        assertThatThrownBy(() -> bookingService.create(EVENT_ID, 4, alice))
                .isInstanceOf(NotEnoughSeatsException.class);

        then(bookingRepository).shouldHaveNoInteractions();
        then(outboxWriter).shouldHaveNoInteractions();
        // rien n'a ete verrouille : il n'y a rien a rendre
        then(seatLockService).should(never()).release(any(UUID.class), anyInt());
    }

    @Test
    @DisplayName("si la transaction echoue, les places verrouillees sont rendues")
    void failedTransactionReleasesTheSeats() {
        given(eventCatalogClient.findById(EVENT_ID)).willReturn(summary(10));
        given(seatLockService.tryLock(EVENT_ID, 3, 10)).willReturn(true);
        given(transactionTemplate.execute(any()))
                .willThrow(new DataIntegrityViolationException("commit refuse"));

        assertThatThrownBy(() -> bookingService.create(EVENT_ID, 3, alice))
                .isInstanceOf(DataIntegrityViolationException.class);

        // sans cette liberation, 3 places resteraient invendables jusqu'a purge de Redis
        then(seatLockService).should().release(EVENT_ID, 3);
    }

    @Test
    @DisplayName("le montant total est calcule a partir du prix unitaire du catalogue")
    void totalAmountComesFromTheCatalogPrice() {
        given(eventCatalogClient.findById(EVENT_ID)).willReturn(summary(10));
        given(seatLockService.tryLock(EVENT_ID, 3, 10)).willReturn(true);
        given(bookingRepository.save(any(Booking.class))).willAnswer(call -> call.getArgument(0));
        runCallbackInline();

        Booking booking = bookingService.create(EVENT_ID, 3, alice);

        assertThat(booking.getTotalAmount()).isEqualByComparingTo("36.00"); // 3 x 12.00
        assertThat(booking.getCustomerEmail()).isEqualTo("alice@example.com");
        then(seatLockService).should(never()).release(eq(EVENT_ID), anyInt());
    }

    @Test
    @DisplayName("si rendre les places echoue aussi, l'appelant voit l'erreur d'origine")
    void failedReleaseDoesNotHideTheOriginalFailure() {
        given(eventCatalogClient.findById(EVENT_ID)).willReturn(summary(10));
        given(seatLockService.tryLock(EVENT_ID, 3, 10)).willReturn(true);
        given(transactionTemplate.execute(any()))
                .willThrow(new DataIntegrityViolationException("commit refuse"));
        willThrow(new DataAccessResourceFailureException("redis injoignable"))
                .given(seatLockService).release(EVENT_ID, 3);

        assertThatThrownBy(() -> bookingService.create(EVENT_ID, 3, alice))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasSuppressedException(new DataAccessResourceFailureException("redis injoignable"));
    }

    @Test
    @DisplayName("erreur au commit mais reservation presente en base : elle est valide et garde ses places")
    void commitErrorWithPersistedBookingKeepsTheSeats() {
        givenCommitFailsAfterTheCallback();
        given(bookingRepository.existsById(any())).willReturn(true);

        Booking booking = bookingService.create(EVENT_ID, 3, alice);

        assertThat(booking.getSeatCount()).isEqualTo(3);
        then(seatLockService).should(never()).release(any(UUID.class), anyInt());
    }

    @Test
    @DisplayName("erreur au commit et reservation absente de la base : les places sont rendues")
    void commitErrorWithoutPersistedBookingReleasesTheSeats() {
        givenCommitFailsAfterTheCallback();
        given(bookingRepository.existsById(any())).willReturn(false);

        assertThatThrownBy(() -> bookingService.create(EVENT_ID, 3, alice))
                .isInstanceOf(TransactionSystemException.class);

        then(seatLockService).should().release(EVENT_ID, 3);
    }

    @Test
    @DisplayName("issue du commit inconnue : on garde les places plutot que de risquer une sur-reservation")
    void unknownCommitOutcomeKeepsTheSeats() {
        givenCommitFailsAfterTheCallback();
        given(bookingRepository.existsById(any()))
                .willThrow(new DataAccessResourceFailureException("base injoignable"));

        assertThatThrownBy(() -> bookingService.create(EVENT_ID, 3, alice))
                .isInstanceOf(TransactionSystemException.class);

        then(seatLockService).should(never()).release(any(UUID.class), anyInt());
    }

    @Test
    @DisplayName("un evenement deja commence est refuse avant tout verrouillage")
    void pastEventIsNotBookable() {
        given(eventCatalogClient.findById(EVENT_ID)).willReturn(new EventSummary(
                EVENT_ID, 10, new BigDecimal("12.00"), Instant.now().minus(Duration.ofMinutes(1))));

        assertThatThrownBy(() -> bookingService.create(EVENT_ID, 1, alice))
                .isInstanceOf(EventNotBookableException.class);

        then(seatLockService).shouldHaveNoInteractions();
        then(bookingRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("un evenement au prix non renseigne (0) est refuse : pas de reservation gratuite")
    void eventWithoutPriceIsNotBookable() {
        given(eventCatalogClient.findById(EVENT_ID)).willReturn(new EventSummary(
                EVENT_ID, 10, BigDecimal.ZERO, Instant.now().plus(Duration.ofDays(30))));

        assertThatThrownBy(() -> bookingService.create(EVENT_ID, 1, alice))
                .isInstanceOf(EventNotBookableException.class);

        then(seatLockService).shouldHaveNoInteractions();
        then(bookingRepository).shouldHaveNoInteractions();
    }

    /** Le callback metier aboutit, puis le commit echoue : cas d'une connexion coupee au COMMIT. */
    private void givenCommitFailsAfterTheCallback() {
        given(eventCatalogClient.findById(EVENT_ID)).willReturn(summary(10));
        given(seatLockService.tryLock(EVENT_ID, 3, 10)).willReturn(true);
        given(bookingRepository.save(any(Booking.class))).willAnswer(call -> call.getArgument(0));
        willAnswer(call -> {
            TransactionCallback<?> callback = call.getArgument(0);
            callback.doInTransaction(mock(TransactionStatus.class));
            throw new TransactionSystemException("connexion perdue pendant le commit");
        }).given(transactionTemplate).execute(any());
    }

    /** Fait executer le callback transactionnel sur place, sans transaction reelle. */
    private void runCallbackInline() {
        willAnswer(call -> {
            TransactionCallback<?> callback = call.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).given(transactionTemplate).execute(any());
    }

    private EventSummary summary(int capacity) {
        return new EventSummary(EVENT_ID, capacity, new BigDecimal("12.00"),
                Instant.now().plus(Duration.ofDays(30)));
    }
}
