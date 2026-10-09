package com.eventhub.event.messaging;

import com.eventhub.event.config.RabbitMQConfig;
import com.eventhub.event.service.SeatAvailabilityService;
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
 * Consomme "booking.confirmed" pour mettre a jour les places restantes (EVT-5).
 *
 * Meme politique d'erreur que les autres consommateurs : un message illisible est rejete
 * sans remise en queue, un doublon est acquitte, une erreur technique est relivree.
 */
@Component
public class BookingConfirmedListener {

    private static final Logger log = LoggerFactory.getLogger(BookingConfirmedListener.class);

    private final SeatAvailabilityService seatAvailabilityService;
    private final ObjectMapper objectMapper;

    public BookingConfirmedListener(SeatAvailabilityService seatAvailabilityService, ObjectMapper objectMapper) {
        this.seatAvailabilityService = seatAvailabilityService;
        this.objectMapper = objectMapper;
    }

    @RabbitListener(queues = RabbitMQConfig.BOOKING_CONFIRMED_QUEUE)
    public void onBookingConfirmed(Message message) {
        BookingConfirmedMessage confirmed = parse(message);
        try {
            seatAvailabilityService.applyConfirmedBooking(
                    confirmed.messageId(), confirmed.eventId(), confirmed.seatCount());
        } catch (DataIntegrityViolationException e) {
            // Deux copies du meme message traitees en parallele : la cle primaire a tranche.
            if (!seatAvailabilityService.isAlreadyApplied(confirmed.messageId())) {
                throw e;
            }
            log.info("Message {} applique par un traitement concurrent : ignore", confirmed.messageId());
        }
    }

    private BookingConfirmedMessage parse(Message message) {
        BookingConfirmedMessage confirmed;
        try {
            confirmed = objectMapper.readValue(message.getBody(), BookingConfirmedMessage.class);
        } catch (IOException e) {
            throw new AmqpRejectAndDontRequeueException("Message booking.confirmed illisible", e);
        }
        if (confirmed == null || confirmed.messageId() == null || confirmed.messageId().isBlank()
                || confirmed.eventId() == null || confirmed.seatCount() == null || confirmed.seatCount() <= 0) {
            throw new AmqpRejectAndDontRequeueException(
                    "Message booking.confirmed incomplet (messageId, eventId et seatCount positif requis) : "
                            + message.getMessageProperties().getMessageId());
        }
        return confirmed;
    }
}
