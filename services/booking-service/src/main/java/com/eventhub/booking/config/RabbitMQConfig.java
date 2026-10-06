package com.eventhub.booking.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(EventHubRabbitProperties.class)
public class RabbitMQConfig {

    /** Queue consommee par payment-service (lot 3). */
    public static final String PAYMENT_BOOKING_REQUESTED_QUEUE = "payment.booking-requested.queue";

    /**
     * L'exchange est declare durable : les messages de l'outbox doivent survivre
     * a un redemarrage du broker, sinon le relais les considererait publies alors
     * qu'ils seraient perdus.
     */
    @Bean
    public TopicExchange bookingExchange(EventHubRabbitProperties properties) {
        return ExchangeBuilder.topicExchange(properties.exchange()).durable(true).build();
    }

    /**
     * booking-service declare ici la topologie de l'exchange qu'il possede, y compris
     * les queues de ses consommateurs.
     *
     * Ce choix est assume : un message publie sur un topic sans binding est purement et
     * simplement jete par RabbitMQ, sans erreur. Laisser chaque consommateur declarer sa
     * propre queue signifierait perdre silencieusement tous les evenements emis tant que
     * ce consommateur n'a jamais demarre. Les consommateurs redeclarent la meme queue de
     * leur cote (l'operation est idempotente), ce qui garde chacun demarrable seul.
     */
    @Bean
    public Queue paymentBookingRequestedQueue() {
        return QueueBuilder.durable(PAYMENT_BOOKING_REQUESTED_QUEUE).build();
    }

    @Bean
    public Binding paymentBookingRequestedBinding(Queue paymentBookingRequestedQueue,
                                                  TopicExchange bookingExchange,
                                                  EventHubRabbitProperties properties) {
        return BindingBuilder.bind(paymentBookingRequestedQueue)
                .to(bookingExchange)
                .with(properties.routingKey().requested());
    }
}
