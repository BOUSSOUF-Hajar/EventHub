package com.eventhub.payment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * PAY-4 : taux d'echec du paiement simule, pour derouler le chemin de compensation
 * en demo sans avoir a provoquer une vraie panne.
 */
@ConfigurationProperties(prefix = "payment")
public record PaymentProperties(double failureRate) {

    public PaymentProperties {
        // Une valeur hors bornes est une erreur de configuration : on refuse de demarrer
        // plutot que de l'interpreter silencieusement comme "jamais" ou "toujours".
        if (Double.isNaN(failureRate) || failureRate < 0.0 || failureRate > 1.0) {
            throw new IllegalArgumentException(
                    "payment.failure-rate doit etre compris entre 0.0 et 1.0, recu : " + failureRate);
        }
    }
}
