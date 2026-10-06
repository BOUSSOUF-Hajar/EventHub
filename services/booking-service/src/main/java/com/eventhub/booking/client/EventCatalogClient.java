package com.eventhub.booking.client;

import com.eventhub.booking.web.error.EventCatalogUnavailableException;
import com.eventhub.booking.web.error.EventNotFoundException;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.UUID;

/**
 * Seul point d'entree vers event-service.
 *
 * C'est un appel REST synchrone assume : la capacite d'un evenement appartient a
 * event-service et booking-service en a besoin *avant* de decider. La contrainte du
 * cahier des charges ("pas d'appel REST direct") porte sur les echanges entre
 * booking / payment / notification, qui eux passent bien par RabbitMQ.
 *
 * L'indisponibilite de event-service est traduite en 503 plutot qu'en 500 : c'est
 * une panne de dependance, pas un bug de booking-service.
 */
@Component
public class EventCatalogClient {

    private final RestClient restClient;

    public EventCatalogClient(RestClient.Builder builder, EventServiceProperties properties) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(properties.connectTimeout())
                .withReadTimeout(properties.readTimeout());

        this.restClient = builder
                .baseUrl(properties.baseUrl())
                .requestFactory(ClientHttpRequestFactories.get(settings))
                .build();
    }

    public EventSummary findById(UUID eventId) {
        try {
            EventSummary summary = restClient.get()
                    .uri("/api/events/{id}", eventId)
                    .retrieve()
                    // Seul 404 veut dire "evenement absent". Les autres erreurs (401, 403, 429...)
                    // remontent en RestClientException, donc en 503 : c'est la dependance qui
                    // est en faute, et repondre "introuvable" masquerait l'incident.
                    .onStatus(status -> status.value() == HttpStatus.NOT_FOUND.value(),
                            (request, response) -> { throw new EventNotFoundException(eventId); })
                    .body(EventSummary.class);

            if (summary == null || summary.totalCapacity() == null) {
                throw new EventNotFoundException(eventId);
            }
            // Un prix absent n'est pas un prix nul : facturer 0 EUR serait pire que refuser.
            // Ce cas signale un event-service d'une version anterieure, donc une dependance
            // incompatible -> 503 plutot qu'une reservation gratuite silencieuse.
            if (summary.unitPrice() == null || summary.startsAt() == null) {
                throw new EventCatalogUnavailableException(eventId,
                        new IllegalStateException("prix unitaire ou date absent de la reponse d'event-service"));
            }
            return summary;
        } catch (EventNotFoundException | EventCatalogUnavailableException e) {
            throw e;
        } catch (RestClientException e) {
            throw new EventCatalogUnavailableException(eventId, e);
        }
    }
}
