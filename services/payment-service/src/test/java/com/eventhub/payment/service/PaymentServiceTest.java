package com.eventhub.payment.service;

import com.eventhub.payment.config.PaymentRabbitProperties;
import com.eventhub.payment.domain.Payment;
import com.eventhub.payment.domain.PaymentStatus;
import com.eventhub.payment.event.PaymentFailedEvent;
import com.eventhub.payment.event.PaymentSucceededEvent;
import com.eventhub.payment.gateway.PaymentGateway;
import com.eventhub.payment.gateway.PaymentGateway.ChargeResult;
import com.eventhub.payment.messaging.BookingRequestedMessage;
import com.eventhub.payment.repository.PaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    private static final UUID BOOKING_ID = UUID.randomUUID();
    private static final BigDecimal AMOUNT = new BigDecimal("50.00");

    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private PaymentGateway paymentGateway;
    @Mock
    private OutboxWriter outboxWriter;

    private PaymentService paymentService;

    private final BookingRequestedMessage request = new BookingRequestedMessage("msg-1", BOOKING_ID, AMOUNT);

    @BeforeEach
    void setUp() {
        PaymentRabbitProperties rabbitProperties = new PaymentRabbitProperties(
                "payment.events",
                new PaymentRabbitProperties.RoutingKeys("payment.succeeded", "payment.failed"),
                new PaymentRabbitProperties.Booking("booking.events", "booking.requested"));

        paymentService = new PaymentService(paymentRepository, paymentGateway, outboxWriter, rabbitProperties);
    }

    @Test
    @DisplayName("paiement accepte : enregistre SUCCEEDED avec un evenement payment.succeeded")
    void approvedChargeIsRecordedWithItsEvent() {
        given(paymentGateway.charge(BOOKING_ID, AMOUNT)).willReturn(ChargeResult.approve());
        given(paymentRepository.saveAndFlush(any(Payment.class))).willAnswer(call -> call.getArgument(0));

        Optional<Payment> payment = paymentService.process(request);

        assertThat(payment).get().satisfies(saved -> {
            assertThat(saved.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
            assertThat(saved.getBookingId()).isEqualTo(BOOKING_ID);
            assertThat(saved.getFailureReason()).isNull();
            assertThat(saved.getRequestMessageId()).isEqualTo("msg-1");
        });

        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        then(outboxWriter).should().append(eq("payment"), any(), eq("payment.succeeded"), event.capture());
        assertThat(event.getValue()).isEqualTo(new PaymentSucceededEvent(null, BOOKING_ID, AMOUNT));
    }

    @Test
    @DisplayName("paiement refuse : c'est un resultat metier, enregistre FAILED et publie, pas une exception")
    void declinedChargeIsRecordedAsABusinessOutcome() {
        given(paymentGateway.charge(BOOKING_ID, AMOUNT)).willReturn(ChargeResult.decline("CARD_DECLINED"));
        given(paymentRepository.saveAndFlush(any(Payment.class))).willAnswer(call -> call.getArgument(0));

        Optional<Payment> payment = paymentService.process(request);

        assertThat(payment).get().satisfies(saved -> {
            assertThat(saved.getStatus()).isEqualTo(PaymentStatus.FAILED);
            assertThat(saved.getFailureReason()).isEqualTo("CARD_DECLINED");
        });

        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        then(outboxWriter).should().append(eq("payment"), any(), eq("payment.failed"), event.capture());
        assertThat(event.getValue())
                .isEqualTo(new PaymentFailedEvent(null, BOOKING_ID, AMOUNT, "CARD_DECLINED"));
    }

    @Test
    @DisplayName("PAY-2 : une reservation deja traitee n'est ni redebitee ni republiee")
    void alreadyProcessedBookingIsSkipped() {
        given(paymentRepository.existsByBookingId(BOOKING_ID)).willReturn(true);

        assertThat(paymentService.process(request)).isEmpty();

        then(paymentGateway).shouldHaveNoInteractions();
        then(outboxWriter).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("violation de la contrainte unique : aucun evenement n'est ecrit, l'erreur remonte au listener")
    void uniqueConstraintViolationWritesNoEvent() {
        given(paymentGateway.charge(BOOKING_ID, AMOUNT)).willReturn(ChargeResult.approve());
        given(paymentRepository.saveAndFlush(any(Payment.class)))
                .willThrow(new DataIntegrityViolationException("uk_payments_booking_id"));

        assertThatThrownBy(() -> paymentService.process(request))
                .isInstanceOf(DataIntegrityViolationException.class);

        then(outboxWriter).shouldHaveNoInteractions();
    }
}
