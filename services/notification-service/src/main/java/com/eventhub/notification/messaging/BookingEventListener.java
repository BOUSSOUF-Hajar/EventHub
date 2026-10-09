package com.eventhub.notification.messaging;

import com.eventhub.notification.config.RabbitMQConfig;
import com.eventhub.notification.service.NotificationService;
import com.eventhub.notification.service.NotificationType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Consomme les fins de Saga publiees par booking-service (NTF-1, NTF-2).
 *
 * <h2>Politique d'erreur</h2>
 * <ul>
 *   <li>message illisible ou incomplet : trace en erreur puis <b>acquitte</b>. On ne leve
 *       pas : toute exception est retentee puis remise en queue (cf. RabbitMQConfig), un
 *       message invalide tournerait donc indefiniment ;</li>
 *   <li>doublon : absorbe par {@link NotificationService} ;</li>
 *   <li>echec d'envoi : l'exception remonte, l'envoi est retente.</li>
 * </ul>
 */
@Component
public class BookingEventListener {

    private static final Logger log = LoggerFactory.getLogger(BookingEventListener.class);

    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;

    public BookingEventListener(NotificationService notificationService, ObjectMapper objectMapper) {
        this.notificationService = notificationService;
        this.objectMapper = objectMapper;
    }

    @RabbitListener(queues = RabbitMQConfig.BOOKING_CONFIRMED_QUEUE)
    public void onBookingConfirmed(Message message) {
        handle(message, NotificationType.BOOKING_CONFIRMED);
    }

    @RabbitListener(queues = RabbitMQConfig.BOOKING_CANCELLED_QUEUE)
    public void onBookingCancelled(Message message) {
        handle(message, NotificationType.BOOKING_CANCELLED);
    }

    private void handle(Message message, NotificationType type) {
        BookingEventMessage event;
        try {
            event = objectMapper.readValue(message.getBody(), BookingEventMessage.class);
        } catch (IOException e) {
            log.error("Evenement {} illisible, ecarte : {}", type, e.getMessage());
            return;
        }
        if (event == null || isBlank(event.messageId()) || event.bookingId() == null
                || isBlank(event.customerEmail())) {
            log.error("Evenement {} incomplet (messageId, bookingId et customerEmail requis), ecarte : {}",
                    type, message.getMessageProperties().getMessageId());
            return;
        }
        notificationService.notify(type, event);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
