package com.eventhub.booking.messaging;

import com.eventhub.booking.config.EventHubRabbitProperties;
import com.eventhub.booking.config.RabbitMQConfig;
import com.eventhub.booking.service.BookingSaga;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Entree de la Saga cote orchestrateur : fait avancer la reservation selon le verdict
 * de payment-service (BKG-4, BKG-5).
 *
 * <h2>Politique d'erreur</h2>
 * <ul>
 *   <li>message illisible, incomplet ou de type inconnu : rejete <b>sans</b> remise en
 *       queue, le relivrer ne le rendrait pas valide ;</li>
 *   <li>doublon ou message en retard : absorbe par {@link BookingSaga}, qui est idempotente ;</li>
 *   <li>erreur technique (base indisponible...) : l'exception remonte, RabbitMQ relivre.</li>
 * </ul>
 */
@Component
public class PaymentResultListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentResultListener.class);

    private final BookingSaga bookingSaga;
    private final ObjectMapper objectMapper;
    private final EventHubRabbitProperties.PaymentRoutingKeys paymentRoutingKeys;

    public PaymentResultListener(BookingSaga bookingSaga,
                                 ObjectMapper objectMapper,
                                 EventHubRabbitProperties rabbitProperties) {
        this.bookingSaga = bookingSaga;
        this.objectMapper = objectMapper;
        this.paymentRoutingKeys = rabbitProperties.payment().routingKey();
    }

    @RabbitListener(queues = RabbitMQConfig.BOOKING_PAYMENT_RESULT_QUEUE)
    public void onPaymentResult(Message message) {
        PaymentResultMessage result = parse(message);

        if (paymentRoutingKeys.succeeded().equals(result.type())) {
            bookingSaga.confirm(result.bookingId());
        } else if (paymentRoutingKeys.failed().equals(result.type())) {
            log.info("Paiement refuse pour la reservation {} (motif : {})", result.bookingId(), result.reason());
            bookingSaga.cancel(result.bookingId(), BookingSaga.REASON_PAYMENT_FAILED);
        } else {
            throw new AmqpRejectAndDontRequeueException(
                    "Type de resultat de paiement inconnu : " + result.type());
        }
    }

    private PaymentResultMessage parse(Message message) {
        PaymentResultMessage result;
        try {
            result = objectMapper.readValue(message.getBody(), PaymentResultMessage.class);
        } catch (IOException e) {
            throw new AmqpRejectAndDontRequeueException("Resultat de paiement illisible", e);
        }
        if (result == null || result.bookingId() == null || result.type() == null) {
            throw new AmqpRejectAndDontRequeueException(
                    "Resultat de paiement incomplet (type et bookingId requis) : "
                            + message.getMessageProperties().getMessageId());
        }
        return result;
    }
}
