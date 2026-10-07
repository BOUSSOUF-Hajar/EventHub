package com.eventhub.booking.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * BKG-3 : filet de securite de la Saga. Si le resultat du paiement n'arrive jamais
 * (payment-service arrete, message perdu), la reservation ne doit pas geler ses places
 * indefiniment.
 *
 * Un job de purge plutot qu'un TTL Redis : le compteur Redis agrege toutes les
 * reservations d'un evenement, un TTL dessus libererait aussi les places confirmees.
 */
@Component
public class BookingExpirationJob {

    private static final Logger log = LoggerFactory.getLogger(BookingExpirationJob.class);

    private final BookingSaga bookingSaga;
    private final Duration paymentTimeout;

    public BookingExpirationJob(BookingSaga bookingSaga,
                                @Value("${eventhub.booking.payment-timeout:15m}") Duration paymentTimeout) {
        this.bookingSaga = bookingSaga;
        this.paymentTimeout = paymentTimeout;
    }

    @Scheduled(
            fixedDelayString = "${eventhub.booking.expiration-poll-interval-ms:60000}",
            initialDelayString = "${eventhub.booking.expiration-initial-delay-ms:60000}")
    public void expireUnpaidBookings() {
        int expired = bookingSaga.expireUnpaid(Instant.now().minus(paymentTimeout));
        if (expired > 0) {
            log.info("{} reservation(s) impayee(s) depuis plus de {} annulee(s)", expired, paymentTimeout);
        }
    }
}
