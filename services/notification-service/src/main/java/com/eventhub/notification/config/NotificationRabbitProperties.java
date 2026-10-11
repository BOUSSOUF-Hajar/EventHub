package com.eventhub.notification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Ce que notification-service doit connaitre de l'exchange de booking-service pour s'y abonner. */
@ConfigurationProperties(prefix = "eventhub.rabbitmq")
public record NotificationRabbitProperties(String exchange, RoutingKeys routingKey) {

    public record RoutingKeys(String confirmed, String cancelled) {
    }
}
