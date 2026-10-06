package com.eventhub.booking.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Noms d'exchange et de routing keys partages avec payment-service et
 * notification-service. Externalises en configuration pour que le contrat
 * de messagerie soit lisible sans ouvrir le code.
 */
@ConfigurationProperties(prefix = "eventhub.rabbitmq")
public record EventHubRabbitProperties(String exchange, RoutingKeys routingKey) {

    public record RoutingKeys(String requested, String confirmed, String cancelled) {
    }
}
