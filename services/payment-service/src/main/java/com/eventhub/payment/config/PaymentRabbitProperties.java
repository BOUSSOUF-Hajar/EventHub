package com.eventhub.payment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Contrat de messagerie de payment-service : l'exchange qu'il possede et ses routing
 * keys, plus ce qu'il lui faut connaitre de celui de booking-service pour s'y abonner.
 */
@ConfigurationProperties(prefix = "eventhub.rabbitmq")
public record PaymentRabbitProperties(String exchange, RoutingKeys routingKey, Booking booking) {

    public record RoutingKeys(String succeeded, String failed) {
    }

    public record Booking(String exchange, String requestedRoutingKey) {
    }
}
