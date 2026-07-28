package com.eventhub.notification.listener;

import com.eventhub.notification.config.RabbitMQConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class BookingEventListener {

    private static final Logger log = LoggerFactory.getLogger(BookingEventListener.class);

    private final JavaMailSender mailSender;

    public BookingEventListener(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    // TODO: remplacer Map<String, Object> par un vrai DTO BookingConfirmedEvent
    // partage (ou duplique) avec le Booking Service, et gerer la deduplication
    // via un identifiant d'evenement (idempotence du consommateur).
    @RabbitListener(queues = RabbitMQConfig.BOOKING_CONFIRMED_QUEUE)
    public void onBookingConfirmed(Map<String, Object> event) {
        log.info("Evenement booking.confirmed recu : {}", event);

        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo("client@example.com"); // TODO: recuperer l'email reel du client
        message.setSubject("Votre reservation est confirmee");
        message.setText("Votre reservation " + event.get("bookingId") + " est confirmee. A bientot !");
        mailSender.send(message);
    }
}
