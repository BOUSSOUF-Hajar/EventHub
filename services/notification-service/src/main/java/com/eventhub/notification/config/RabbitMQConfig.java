package com.eventhub.notification.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.retry.ImmediateRequeueMessageRecoverer;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Topologie redeclaree a l'identique de celle de booking-service, proprietaire de
 * l'exchange : l'operation est idempotente et notification-service reste demarrable seul.
 */
@Configuration
@EnableConfigurationProperties({NotificationRabbitProperties.class, NotificationProperties.class})
public class RabbitMQConfig {

    public static final String BOOKING_CONFIRMED_QUEUE = "notification.booking-confirmed.queue";
    public static final String BOOKING_CANCELLED_QUEUE = "notification.booking-cancelled.queue";

    @Bean
    public TopicExchange bookingExchange(NotificationRabbitProperties properties) {
        return ExchangeBuilder.topicExchange(properties.exchange()).durable(true).build();
    }

    @Bean
    public Queue bookingConfirmedQueue() {
        return QueueBuilder.durable(BOOKING_CONFIRMED_QUEUE).build();
    }

    @Bean
    public Binding bookingConfirmedBinding(Queue bookingConfirmedQueue,
                                           TopicExchange bookingExchange,
                                           NotificationRabbitProperties properties) {
        return BindingBuilder.bind(bookingConfirmedQueue)
                .to(bookingExchange)
                .with(properties.routingKey().confirmed());
    }

    @Bean
    public Queue bookingCancelledQueue() {
        return QueueBuilder.durable(BOOKING_CANCELLED_QUEUE).build();
    }

    @Bean
    public Binding bookingCancelledBinding(Queue bookingCancelledQueue,
                                           TopicExchange bookingExchange,
                                           NotificationRabbitProperties properties) {
        return BindingBuilder.bind(bookingCancelledQueue)
                .to(bookingExchange)
                .with(properties.routingKey().cancelled());
    }

    /**
     * Par defaut, une fois les essais epuises, Spring AMQP rejette le message sans le
     * remettre en queue : la notification serait perdue apres 35 s de panne SMTP. On le
     * remet donc en queue, ce qui relance un cycle d'essais espaces.
     *
     * Contrepartie : un message qui ne pourra jamais etre traite tournerait sans fin.
     * C'est pourquoi le listener ecarte lui-meme les messages illisibles, sans lever.
     */
    @Bean
    public MessageRecoverer messageRecoverer() {
        return new ImmediateRequeueMessageRecoverer();
    }
}
