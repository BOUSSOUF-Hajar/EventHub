package com.eventhub.booking.service;

import com.eventhub.booking.web.error.SeatLockUnavailableException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;

/**
 * Une panne de Redis ne doit jamais etre presentee au client comme un "complet".
 * Ce cas est difficile a provoquer avec un vrai conteneur, d'ou le double de test.
 */
@ExtendWith(MockitoExtension.class)
@SuppressWarnings({"unchecked", "rawtypes"}) // RedisTemplate.execute est generique, les matchers Mockito ne le sont pas
class SeatLockServiceFailureTest {

    private static final UUID EVENT_ID = UUID.randomUUID();

    @Mock
    private StringRedisTemplate redisTemplate;

    @Test
    @DisplayName("un resultat absent de Redis remonte comme une panne, pas comme un refus")
    void nullScriptResultIsReportedAsAnOutage() {
        given(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .willReturn(null);

        assertThatThrownBy(() -> new SeatLockService(redisTemplate).tryLock(EVENT_ID, 1, 10))
                .isInstanceOf(SeatLockUnavailableException.class);
    }

    @Test
    @DisplayName("une connexion Redis rompue remonte comme une panne")
    void connectionFailureIsReportedAsAnOutage() {
        willThrow(new RedisConnectionFailureException("redis down"))
                .given(redisTemplate).execute(any(RedisScript.class), anyList(), any(Object[].class));

        assertThatThrownBy(() -> new SeatLockService(redisTemplate).tryLock(EVENT_ID, 1, 10))
                .isInstanceOf(SeatLockUnavailableException.class)
                .hasRootCauseInstanceOf(RedisConnectionFailureException.class);
    }

    @Test
    @DisplayName("le script recoit la cle de l'evenement, le nombre de places et la capacite")
    void scriptIsCalledWithEventKeyAndCapacity() {
        given(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .willReturn(3L);

        new SeatLockService(redisTemplate).tryLock(EVENT_ID, 3, 10);

        then(redisTemplate).should().execute(
                any(RedisScript.class), eq(List.of("seats:" + EVENT_ID)), eq("3"), eq("10"));
    }
}
