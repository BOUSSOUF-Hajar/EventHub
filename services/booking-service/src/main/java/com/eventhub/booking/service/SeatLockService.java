package com.eventhub.booking.service;

import com.eventhub.booking.web.error.SeatLockUnavailableException;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Verrou distribue sur la disponibilite d'un evenement.
 *
 * Cle Redis : "seats:{eventId}" -> nombre de places actuellement prises
 * (reservations PENDING, AWAITING_PAYMENT et CONFIRMED confondues).
 *
 * <h2>Pourquoi Redis et pas la base ?</h2>
 * La capacite appartient a event-service, la reservation a booking-service : il n'y a
 * pas de ligne commune a verrouiller en base. Redis, mono-thread, donne un point de
 * serialisation partage entre toutes les instances de booking-service.
 *
 * <h2>Pourquoi un script Lua et pas WATCH/MULTI/EXEC ?</h2>
 * Redis execute un script Lua de facon atomique : le "lire, comparer, incrementer"
 * ne peut pas etre entrelace avec une autre requete. Avec WATCH/MULTI/EXEC il faut
 * au contraire gerer une boucle de retry sur conflit optimiste, ce qui degrade
 * fortement sous forte contention -- exactement le cas qui nous interesse ici
 * (la ruee sur les dernieres places).
 *
 * <h2>Limite connue</h2>
 * La cle n'a volontairement pas de TTL : un TTL sur le compteur agrege ferait
 * "reapparaitre" d'un coup des places deja confirmees. L'expiration des reservations
 * non payees est traitee reservation par reservation (cf. lot 3, BKG-3). En
 * contrepartie, si Redis est purge, le compteur doit etre reconstruit depuis la base
 * (somme des places des reservations non annulees).
 */
@Service
public class SeatLockService {

    private static final String KEY_PREFIX = "seats:";
    private static final long REFUSED = -1L;

    /**
     * Renvoie -1 si la capacite est depassee, sinon le nouveau total de places prises.
     * `redis.call('GET')` renvoie false quand la cle est absente, d'ou le `or '0'`.
     */
    private static final RedisScript<Long> RESERVE_SCRIPT = new DefaultRedisScript<>("""
            local reserved = tonumber(redis.call('GET', KEYS[1]) or '0')
            local count = tonumber(ARGV[1])
            local capacity = tonumber(ARGV[2])
            if reserved + count > capacity then
              return -1
            end
            return redis.call('INCRBY', KEYS[1], count)
            """, Long.class);

    /** Liberation bornee a zero, pour qu'un double release ne rende pas le compteur negatif. */
    private static final RedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>("""
            local reserved = tonumber(redis.call('GET', KEYS[1]) or '0')
            local released = reserved - tonumber(ARGV[1])
            if released < 0 then
              released = 0
            end
            redis.call('SET', KEYS[1], released)
            return released
            """, Long.class);

    private final StringRedisTemplate redis;

    public SeatLockService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * Tente de reserver {@code count} places pour un evenement.
     *
     * @return true si les places ont ete verrouillees, false s'il n'en reste pas assez
     */
    public boolean tryLock(UUID eventId, int count, int totalCapacity) {
        Long result;
        try {
            result = redis.execute(
                    RESERVE_SCRIPT,
                    List.of(key(eventId)),
                    String.valueOf(count),
                    String.valueOf(totalCapacity));
        } catch (DataAccessException e) {
            throw new SeatLockUnavailableException(eventId, e);
        }
        // Un resultat absent n'est pas un refus : c'est une panne du verrou. La confondre
        // avec "complet" annoncerait a tort un evenement sold out et masquerait l'incident.
        if (result == null) {
            throw new SeatLockUnavailableException(eventId);
        }
        return result != REFUSED;
    }

    /** Action compensatoire : rend les places au stock disponible. */
    public void release(UUID eventId, int count) {
        redis.execute(RELEASE_SCRIPT, List.of(key(eventId)), String.valueOf(count));
    }

    /** Nombre de places actuellement prises pour un evenement (lecture, pour les tests et le debug). */
    public long reservedSeats(UUID eventId) {
        String value = redis.opsForValue().get(key(eventId));
        return value == null ? 0L : Long.parseLong(value);
    }

    private String key(UUID eventId) {
        return KEY_PREFIX + eventId;
    }
}
