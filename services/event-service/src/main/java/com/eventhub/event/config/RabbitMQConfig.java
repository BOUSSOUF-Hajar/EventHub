package com.eventhub.event.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Topologie redeclaree a l'identique de celle de booking-service, proprietaire de
 * l'exchange : l'operation est idempotente et event-service reste demarrable seul.
 */
@Configuration
public class RabbitMQConfig {

    public static final String BOOKING_CONFIRMED_QUEUE = "event.booking-confirmed.queue";

    @Bean
    public TopicExchange bookingExchange(@Value("${eventhub.rabbitmq.exchange}") String exchange) {
        return ExchangeBuilder.topicExchange(exchange).durable(true).build();
    }

    @Bean
    public Queue bookingConfirmedQueue() {
        return QueueBuilder.durable(BOOKING_CONFIRMED_QUEUE).build();
    }

    @Bean
    public Binding bookingConfirmedBinding(Queue bookingConfirmedQueue,
                                           TopicExchange bookingExchange,
                                           @Value("${eventhub.rabbitmq.confirmed-routing-key}") String routingKey) {
        return BindingBuilder.bind(bookingConfirmedQueue).to(bookingExchange).with(routingKey);
    }
}
