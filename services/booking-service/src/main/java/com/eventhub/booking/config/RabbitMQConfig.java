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

    /** Queue consommee par payment-service. */
    public static final String PAYMENT_BOOKING_REQUESTED_QUEUE = "payment.booking-requested.queue";

    /** Queues consommees par notification-service (lot 4). */
    public static final String NOTIFICATION_BOOKING_CONFIRMED_QUEUE = "notification.booking-confirmed.queue";
    public static final String NOTIFICATION_BOOKING_CANCELLED_QUEUE = "notification.booking-cancelled.queue";

    /** Queue d'entree de la Saga : les resultats de paiement publies par payment-service. */
    public static final String BOOKING_PAYMENT_RESULT_QUEUE = "booking.payment-result.queue";

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

    /**
     * Meme raison pour les queues de notification-service : sans elles, le relais d'outbox
     * (flag mandatory) ne pourrait jamais marquer "booking.confirmed" comme publie. Tant que
     * le lot 4 n'est pas livre, les evenements s'y accumulent au lieu d'etre perdus.
     */
    @Bean
    public Queue notificationBookingConfirmedQueue() {
        return QueueBuilder.durable(NOTIFICATION_BOOKING_CONFIRMED_QUEUE).build();
    }

    @Bean
    public Binding notificationBookingConfirmedBinding(Queue notificationBookingConfirmedQueue,
                                                       TopicExchange bookingExchange,
                                                       EventHubRabbitProperties properties) {
        return BindingBuilder.bind(notificationBookingConfirmedQueue)
                .to(bookingExchange)
                .with(properties.routingKey().confirmed());
    }

    @Bean
    public Queue notificationBookingCancelledQueue() {
        return QueueBuilder.durable(NOTIFICATION_BOOKING_CANCELLED_QUEUE).build();
    }

    @Bean
    public Binding notificationBookingCancelledBinding(Queue notificationBookingCancelledQueue,
                                                       TopicExchange bookingExchange,
                                                       EventHubRabbitProperties properties) {
        return BindingBuilder.bind(notificationBookingCancelledQueue)
                .to(bookingExchange)
                .with(properties.routingKey().cancelled());
    }

    /**
     * Cote consommation, on redeclare a l'identique ce que payment-service declare deja
     * pour son propre exchange : booking-service reste ainsi demarrable seul.
     */
    @Bean
    public TopicExchange paymentExchange(EventHubRabbitProperties properties) {
        return ExchangeBuilder.topicExchange(properties.payment().exchange()).durable(true).build();
    }

    @Bean
    public Queue bookingPaymentResultQueue() {
        return QueueBuilder.durable(BOOKING_PAYMENT_RESULT_QUEUE).build();
    }

    @Bean
    public Binding paymentSucceededBinding(Queue bookingPaymentResultQueue,
                                           TopicExchange paymentExchange,
                                           EventHubRabbitProperties properties) {
        return BindingBuilder.bind(bookingPaymentResultQueue)
                .to(paymentExchange)
                .with(properties.payment().routingKey().succeeded());
    }

    @Bean
    public Binding paymentFailedBinding(Queue bookingPaymentResultQueue,
                                        TopicExchange paymentExchange,
                                        EventHubRabbitProperties properties) {
        return BindingBuilder.bind(bookingPaymentResultQueue)
                .to(paymentExchange)
                .with(properties.payment().routingKey().failed());
    }
}
