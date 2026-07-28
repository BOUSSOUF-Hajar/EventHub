package com.eventhub.notification.config;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    public static final String BOOKING_EXCHANGE = "booking.events";
    public static final String BOOKING_CONFIRMED_QUEUE = "notification.booking-confirmed.queue";
    public static final String BOOKING_CONFIRMED_ROUTING_KEY = "booking.confirmed";

    @Bean
    public TopicExchange bookingExchange() {
        return new TopicExchange(BOOKING_EXCHANGE);
    }

    @Bean
    public Queue bookingConfirmedQueue() {
        return new Queue(BOOKING_CONFIRMED_QUEUE, true);
    }

    @Bean
    public Binding bookingConfirmedBinding(Queue bookingConfirmedQueue, TopicExchange bookingExchange) {
        return BindingBuilder.bind(bookingConfirmedQueue)
                .to(bookingExchange)
                .with(BOOKING_CONFIRMED_ROUTING_KEY);
    }

    // TODO: ajouter de la meme facon une queue/binding pour "payment.failed"
    // afin de notifier le client en cas d'echec de paiement.
}
