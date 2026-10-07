package com.eventhub.payment.config;

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
@EnableConfigurationProperties({PaymentRabbitProperties.class, PaymentProperties.class})
public class RabbitMQConfig {

    /** Queue d'entree : les demandes de paiement emises par booking-service. */
    public static final String BOOKING_REQUESTED_QUEUE = "payment.booking-requested.queue";

    /** Queue de sortie : les resultats de paiement, consommes par booking-service. */
    public static final String PAYMENT_RESULT_QUEUE = "booking.payment-result.queue";

    @Bean
    public TopicExchange paymentExchange(PaymentRabbitProperties properties) {
        return ExchangeBuilder.topicExchange(properties.exchange()).durable(true).build();
    }

    /**
     * Meme convention que dans booking-service : le service qui possede un exchange declare
     * aussi les queues de ses consommateurs, sinon un resultat de paiement publie avant le
     * premier demarrage de booking-service serait jete sans erreur par RabbitMQ.
     */
    @Bean
    public Queue paymentResultQueue() {
        return QueueBuilder.durable(PAYMENT_RESULT_QUEUE).build();
    }

    @Bean
    public Binding paymentSucceededBinding(Queue paymentResultQueue,
                                           TopicExchange paymentExchange,
                                           PaymentRabbitProperties properties) {
        return BindingBuilder.bind(paymentResultQueue)
                .to(paymentExchange)
                .with(properties.routingKey().succeeded());
    }

    @Bean
    public Binding paymentFailedBinding(Queue paymentResultQueue,
                                        TopicExchange paymentExchange,
                                        PaymentRabbitProperties properties) {
        return BindingBuilder.bind(paymentResultQueue)
                .to(paymentExchange)
                .with(properties.routingKey().failed());
    }

    /**
     * Cote consommation, on redeclare a l'identique ce que booking-service declare deja :
     * l'operation est idempotente et permet a payment-service de demarrer seul.
     */
    @Bean
    public TopicExchange bookingExchange(PaymentRabbitProperties properties) {
        return ExchangeBuilder.topicExchange(properties.booking().exchange()).durable(true).build();
    }

    @Bean
    public Queue bookingRequestedQueue() {
        return QueueBuilder.durable(BOOKING_REQUESTED_QUEUE).build();
    }

    @Bean
    public Binding bookingRequestedBinding(Queue bookingRequestedQueue,
                                           TopicExchange bookingExchange,
                                           PaymentRabbitProperties properties) {
        return BindingBuilder.bind(bookingRequestedQueue)
                .to(bookingExchange)
                .with(properties.booking().requestedRoutingKey());
    }
}
