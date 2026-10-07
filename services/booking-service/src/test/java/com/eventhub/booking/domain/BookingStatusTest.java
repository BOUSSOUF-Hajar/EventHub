package com.eventhub.booking.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * La machine a etats est le garde-fou de la Saga : elle doit resister a des messages
 * dupliques ou arrivant dans le desordre, situation normale avec RabbitMQ.
 */
class BookingStatusTest {

    @Test
    @DisplayName("le chemin nominal PENDING -> AWAITING_PAYMENT -> CONFIRMED est autorise")
    void nominalPathIsAllowed() {
        Booking booking = newBooking();

        assertThat(booking.transitionTo(BookingStatus.AWAITING_PAYMENT)).isTrue();
        assertThat(booking.transitionTo(BookingStatus.CONFIRMED)).isTrue();
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
    }

    @Test
    @DisplayName("rejouer la meme transition est sans effet et ne leve pas (idempotence)")
    void replayingTheSameTransitionIsANoOp() {
        Booking booking = newBooking();
        booking.transitionTo(BookingStatus.AWAITING_PAYMENT);

        assertThat(booking.transitionTo(BookingStatus.AWAITING_PAYMENT)).isFalse();
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.AWAITING_PAYMENT);
    }

    @Test
    @DisplayName("un payment.failed arrive apres un payment.succeeded ne peut pas annuler la reservation")
    void confirmedBookingCannotBeCancelledAfterwards() {
        Booking booking = newBooking();
        booking.transitionTo(BookingStatus.CONFIRMED);

        assertThatThrownBy(() -> booking.transitionTo(BookingStatus.CANCELLED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Transition interdite");
    }

    @Test
    @DisplayName("une reservation annulee ne peut plus etre confirmee")
    void cancelledBookingCannotBeConfirmed() {
        Booking booking = newBooking();
        booking.transitionTo(BookingStatus.CANCELLED);

        assertThatThrownBy(() -> booking.transitionTo(BookingStatus.CONFIRMED))
                .isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @EnumSource(value = BookingStatus.class, names = {"CONFIRMED", "CANCELLED"})
    @DisplayName("les etats terminaux n'autorisent aucune sortie")
    void terminalStatesAreDeadEnds(BookingStatus terminal) {
        assertThat(terminal.isTerminal()).isTrue();
        for (BookingStatus target : BookingStatus.values()) {
            assertThat(terminal.canTransitionTo(target)).isFalse();
        }
    }

    private Booking newBooking() {
        return new Booking(UUID.randomUUID(), UUID.randomUUID(), "alice@example.com", 2, new BigDecimal("50.00"));
    }
}
