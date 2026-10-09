package com.eventhub.payment.messaging;

import com.eventhub.payment.config.RabbitMQConfig;
import com.eventhub.payment.service.PaymentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Point d'entree de payment-service : il n'expose aucun endpoint HTTP de paiement, tout
 * passe par RabbitMQ (contrainte "pas d'appel REST direct entre services").
 *
 * <h2>Politique d'erreur</h2>
 * <ul>
 *   <li>message illisible ou incomplet : rejete <b>sans</b> remise en queue. Le relivrer
 *       ne le rendrait pas valide, il bloquerait seulement la queue ;</li>
 *   <li>doublon : acquitte en silence, c'est le fonctionnement normal en "at least once" ;</li>
 *   <li>toute autre erreur (base indisponible...) : l'exception remonte, RabbitMQ relivre.</li>
 * </ul>
 */
@Component
public class BookingRequestedListener {

    private static final Logger log = LoggerFactory.getLogger(BookingRequestedListener.class);

    private final PaymentService paymentService;
    private final ObjectMapper objectMapper;

    public BookingRequestedListener(PaymentService paymentService, ObjectMapper objectMapper) {
        this.paymentService = paymentService;
        this.objectMapper = objectMapper;
    }

    @RabbitListener(queues = RabbitMQConfig.BOOKING_REQUESTED_QUEUE)
    public void onBookingRequested(Message message) {
        BookingRequestedMessage request = parse(message);
        try {
            paymentService.process(request);
        } catch (DataIntegrityViolationException e) {
            // Deux livraisons du meme message traitees en parallele : la contrainte unique
            // a tranche. Si le paiement existe bien, c'est un doublon ; sinon l'erreur est
            // autre chose et doit remonter.
            if (!paymentService.isAlreadyProcessed(request.bookingId())) {
                throw e;
            }
            log.info("Paiement concurrent deja enregistre pour la reservation {} : message {} ignore",
                    request.bookingId(), request.messageId());
        }
    }

    private BookingRequestedMessage parse(Message message) {
        BookingRequestedMessage request;
        try {
            request = objectMapper.readValue(message.getBody(), BookingRequestedMessage.class);
        } catch (IOException e) {
            throw new AmqpRejectAndDontRequeueException("Message booking.requested illisible", e);
        }
        if (request == null || request.bookingId() == null
                || request.amount() == null || request.amount().signum() <= 0) {
            throw new AmqpRejectAndDontRequeueException(
                    "Message booking.requested incomplet (bookingId et montant positif requis) : "
                            + message.getMessageProperties().getMessageId());
        }
        return request;
    }
}
