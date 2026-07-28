package com.eventhub.payment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * TODO (a implementer dans la phase 5 de la roadmap, voir CAHIER_DES_CHARGES.md) :
 *  - entite Payment (id, bookingId, amount, status, idempotencyKey)
 *  - endpoint POST /api/payments qui simule un appel a un PSP (Stripe en mode test par ex.)
 *  - cle d'idempotence obligatoire en en-tete pour eviter le double-paiement
 *  - listener RabbitMQ sur "booking.requested" -> tente le paiement -> publie
 *    "payment.succeeded" ou "payment.failed"
 */
@SpringBootApplication
public class PaymentServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentServiceApplication.class, args);
    }
}
