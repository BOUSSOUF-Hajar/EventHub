package com.eventhub.payment.gateway;

import com.eventhub.payment.config.PaymentProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * PSP simule (PAY-1) : refuse une part configurable des paiements (PAY-4).
 * La cle d'idempotence est ignoree, il n'y a aucun debit reel a dedoublonner.
 */
@Component
public class SimulatedPaymentGateway implements PaymentGateway {

    static final String DECLINE_REASON = "CARD_DECLINED";

    private final double failureRate;

    public SimulatedPaymentGateway(PaymentProperties properties) {
        this.failureRate = properties.failureRate();
    }

    @Override
    public ChargeResult charge(UUID idempotencyKey, BigDecimal amount) {
        // nextDouble() est dans [0, 1) : un taux de 0.0 ne refuse jamais, 1.0 refuse toujours.
        if (ThreadLocalRandom.current().nextDouble() < failureRate) {
            return ChargeResult.decline(DECLINE_REASON);
        }
        return ChargeResult.approve();
    }
}
