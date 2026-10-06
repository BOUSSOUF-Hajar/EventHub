package com.eventhub.booking.service;

import com.eventhub.booking.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BKG-2 : le verrou Redis doit empecher la sur-reservation.
 *
 * Un test sequentiel ne prouverait rien ici : c'est l'entrelacement de deux
 * "lire puis ecrire" qui cree le bug qu'on cherche a exclure. On synchronise donc
 * les threads sur une barriere pour qu'ils attaquent Redis au meme instant.
 */
class SeatLockServiceConcurrencyTest extends AbstractIntegrationTest {

    @Autowired
    private SeatLockService seatLockService;

    @Test
    @DisplayName("deux demandes simultanees sur la derniere place : une seule reussit")
    void onlyOneOfTwoConcurrentRequestsGetsTheLastSeat() throws Exception {
        UUID eventId = UUID.randomUUID();

        long granted = runConcurrently(2, () -> seatLockService.tryLock(eventId, 1, 1));

        assertThat(granted).isEqualTo(1);
        assertThat(seatLockService.reservedSeats(eventId)).isEqualTo(1);
    }

    @Test
    @DisplayName("sous forte contention, le nombre de places attribuees n'excede jamais la capacite")
    void neverOversellsUnderHeavyContention() throws Exception {
        UUID eventId = UUID.randomUUID();
        int capacity = 10;

        long granted = runConcurrently(50, () -> seatLockService.tryLock(eventId, 1, capacity));

        assertThat(granted).isEqualTo(capacity);
        assertThat(seatLockService.reservedSeats(eventId)).isEqualTo(capacity);
    }

    @Test
    @DisplayName("une demande de plusieurs places est tout-ou-rien")
    void multiSeatRequestIsAllOrNothing() {
        UUID eventId = UUID.randomUUID();

        assertThat(seatLockService.tryLock(eventId, 3, 4)).isTrue();
        // il reste 1 place : une demande de 2 doit etre refusee en bloc
        assertThat(seatLockService.tryLock(eventId, 2, 4)).isFalse();
        assertThat(seatLockService.reservedSeats(eventId)).isEqualTo(3);

        assertThat(seatLockService.tryLock(eventId, 1, 4)).isTrue();
        assertThat(seatLockService.reservedSeats(eventId)).isEqualTo(4);
    }

    @Test
    @DisplayName("la liberation rend les places et ne descend jamais sous zero")
    void releaseGivesSeatsBackAndIsBoundedAtZero() {
        UUID eventId = UUID.randomUUID();
        seatLockService.tryLock(eventId, 2, 5);

        seatLockService.release(eventId, 2);
        assertThat(seatLockService.reservedSeats(eventId)).isZero();

        // un double release (message redelivre) ne doit pas creer des places fantomes
        seatLockService.release(eventId, 2);
        assertThat(seatLockService.reservedSeats(eventId)).isZero();
    }

    /** Lance {@code threads} appels simultanes et renvoie le nombre de succes. */
    private long runConcurrently(int threads, Callable<Boolean> action) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier startLine = new CyclicBarrier(threads);
        try {
            List<Future<Boolean>> results = pool.invokeAll(IntStream.range(0, threads)
                    .<Callable<Boolean>>mapToObj(i -> () -> {
                        startLine.await(10, TimeUnit.SECONDS);
                        return action.call();
                    })
                    .toList());

            long successes = 0;
            for (Future<Boolean> result : results) {
                if (Boolean.TRUE.equals(result.get(10, TimeUnit.SECONDS))) {
                    successes++;
                }
            }
            return successes;
        } finally {
            pool.shutdownNow();
        }
    }
}
