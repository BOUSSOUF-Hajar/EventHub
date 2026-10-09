package com.eventhub.notification.service;

import com.eventhub.notification.config.NotificationProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Memoire des evenements deja notifies (NTF-3), cle = messageId de l'evenement.
 *
 * <h2>Pourquoi Redis</h2>
 * notification-service n'a pas de base : il ne possede aucune donnee metier. Redis suffit
 * pour une memoire a duree limitee, et elle est partagee entre toutes les instances.
 *
 * <h2>Pourquoi deux etats</h2>
 * Un envoi d'email et une ecriture Redis ne peuvent pas etre atomiques. Avec un seul
 * marqueur, il faut choisir entre deux defauts :
 * <ul>
 *   <li>marquer puis envoyer : un crash entre les deux perd l'email ;</li>
 *   <li>envoyer puis marquer : deux copies du meme evenement traitees en parallele
 *       passent toutes les deux la verification, le client recoit deux emails.</li>
 * </ul>
 * On reserve donc d'abord l'evenement avec un bail court (PROCESSING), puis on le marque
 * definitivement (DONE) une fois l'email parti. Une copie concurrente voit le bail et
 * attend ; si le premier traitement a plante, le bail expire et la copie prend le relais.
 * Il reste un seul cas de doublon : un crash juste apres l'envoi et avant le marquage.
 * Entre perdre un email et l'envoyer deux fois, on prefere le doublon.
 */
@Component
public class ProcessedEventStore {

    public enum Claim {
        /** L'evenement est a nous : il faut envoyer la notification. */
        ACQUIRED,
        /** Deja notifie : rien a faire. */
        DUPLICATE,
        /** Une autre copie est en cours de traitement : reessayer plus tard. */
        IN_PROGRESS
    }

    private static final String KEY_PREFIX = "notification:event:";
    private static final String PROCESSING = "PROCESSING";
    private static final String DONE = "DONE";

    private final StringRedisTemplate redis;
    private final Duration lease;
    private final Duration retention;

    public ProcessedEventStore(StringRedisTemplate redis, NotificationProperties properties) {
        this.redis = redis;
        this.lease = properties.deduplication().lease();
        this.retention = properties.deduplication().retention();
    }

    public Claim claim(String eventId) {
        // SET NX : la reservation est atomique, deux instances ne peuvent pas l'obtenir.
        Boolean acquired = redis.opsForValue().setIfAbsent(key(eventId), PROCESSING, lease);
        if (Boolean.TRUE.equals(acquired)) {
            return Claim.ACQUIRED;
        }
        return DONE.equals(redis.opsForValue().get(key(eventId))) ? Claim.DUPLICATE : Claim.IN_PROGRESS;
    }

    public void markDone(String eventId) {
        redis.opsForValue().set(key(eventId), DONE, retention);
    }

    /** Rend la reservation apres un echec d'envoi, pour que le prochain essai puisse la reprendre. */
    public void release(String eventId) {
        redis.delete(key(eventId));
    }

    private String key(String eventId) {
        return KEY_PREFIX + eventId;
    }
}
