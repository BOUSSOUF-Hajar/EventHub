package com.eventhub.notification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "eventhub.notification")
public record NotificationProperties(String from, Deduplication deduplication) {

    /**
     * @param lease     duree de reservation d'un evenement en cours de traitement
     * @param retention duree pendant laquelle un evenement deja notifie est memorise
     */
    public record Deduplication(Duration lease, Duration retention) {
    }
}
