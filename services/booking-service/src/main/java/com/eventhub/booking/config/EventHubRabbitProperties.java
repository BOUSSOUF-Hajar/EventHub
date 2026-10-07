package com.eventhub.booking.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Noms d'exchange et de routing keys partages avec payment-service et
 * notification-service. Externalises en configuration pour que le contrat
 * de messagerie soit lisible sans ouvrir le code.
 */
@ConfigurationProperties(prefix = "eventhub.rabbitmq")
public record EventHubRabbitProperties(String exchange, RoutingKeys routingKey, Payment payment) {

    public record RoutingKeys(String requested, String confirmed, String cancelled) {
    }

    /** Ce que booking-service doit connaitre de l'exchange de payment-service pour s'y abonner. */
    public record Payment(String exchange, PaymentRoutingKeys routingKey) {
    }

    public record PaymentRoutingKeys(String succeeded, String failed) {
    }
}
