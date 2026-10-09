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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    private static final String AGGREGATE_TYPE = "payment";

    private final PaymentRepository paymentRepository;
    private final PaymentGateway paymentGateway;
    private final OutboxWriter outboxWriter;
    private final PaymentRabbitProperties rabbitProperties;

    public PaymentService(PaymentRepository paymentRepository,
                          PaymentGateway paymentGateway,
                          OutboxWriter outboxWriter,
                          PaymentRabbitProperties rabbitProperties) {
        this.paymentRepository = paymentRepository;
        this.paymentGateway = paymentGateway;
        this.outboxWriter = outboxWriter;
        this.rabbitProperties = rabbitProperties;
    }

    /**
     * Tente le paiement d'une reservation (PAY-1) et enregistre son resultat avec
     * l'evenement a publier, dans une seule transaction (PAY-3).
     *
     * Un refus du PSP n'est pas une erreur technique : c'est un resultat metier, persiste
     * et publie comme un succes. Seule une exception (base ou PSP injoignable) annule la
     * transaction, et le message sera alors relivre.
     *
     * @return le paiement cree, ou vide si cette reservation avait deja ete traitee (PAY-2)
     */
    @Transactional
    public Optional<Payment> process(BookingRequestedMessage request) {
        UUID bookingId = request.bookingId();
        if (paymentRepository.existsByBookingId(bookingId)) {
            log.info("Demande de paiement deja traitee pour la reservation {} : message {} ignore",
                    bookingId, request.messageId());
            return Optional.empty();
        }

        ChargeResult charge = paymentGateway.charge(bookingId, request.amount());

        // saveAndFlush et non save : la violation de la contrainte unique doit remonter
        // ici, pas au commit, pour que l'appelant puisse la reconnaitre comme un doublon.
        Payment payment = paymentRepository.saveAndFlush(charge.approved()
                ? Payment.succeeded(bookingId, request.amount(), request.messageId())
                : Payment.failed(bookingId, request.amount(), charge.declineReason(), request.messageId()));

        if (payment.getStatus() == PaymentStatus.SUCCEEDED) {
            outboxWriter.append(AGGREGATE_TYPE, payment.getId(),
                    rabbitProperties.routingKey().succeeded(),
                    new PaymentSucceededEvent(payment.getId(), bookingId, payment.getAmount()));
        } else {
            outboxWriter.append(AGGREGATE_TYPE, payment.getId(),
                    rabbitProperties.routingKey().failed(),
                    new PaymentFailedEvent(payment.getId(), bookingId, payment.getAmount(),
                            payment.getFailureReason()));
        }

        log.info("Paiement {} {} pour la reservation {} ({})",
                payment.getId(), payment.getStatus(), bookingId, payment.getAmount());
        return Optional.of(payment);
    }

    @Transactional(readOnly = true)
    public boolean isAlreadyProcessed(UUID bookingId) {
        return paymentRepository.existsByBookingId(bookingId);
    }
}
