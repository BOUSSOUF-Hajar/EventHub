package com.eventhub.booking.client;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Les delais ont une valeur par defaut explicite : sans eux, un event-service qui
 * accepte la connexion mais ne repond jamais bloquerait indefiniment un thread Tomcat
 * du chemin de reservation.
 */
@ConfigurationProperties(prefix = "eventhub.event-service")
public record EventServiceProperties(String baseUrl, Duration connectTimeout, Duration readTimeout) {

    public EventServiceProperties {
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(2) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(3) : readTimeout;
    }
}
