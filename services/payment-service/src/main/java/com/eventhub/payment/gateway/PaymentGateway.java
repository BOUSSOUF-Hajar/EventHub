package com.eventhub.payment.gateway;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Frontiere avec le prestataire de paiement (PSP). L'implementation actuelle est
 * simulee ; un vrai PSP se brancherait ici sans toucher au reste du service.
 */
public interface PaymentGateway {

    /**
     * @param idempotencyKey transmise au PSP pour qu'il ecarte lui aussi un debit rejoue.
     *                       C'est ce qui couvre la fenetre que la contrainte unique en base
     *                       ne peut pas couvrir : un debit accepte suivi d'un crash avant
     *                       le commit, donc d'une relivraison du message.
     */
    ChargeResult charge(UUID idempotencyKey, BigDecimal amount);

    record ChargeResult(boolean approved, String declineReason) {

        public static ChargeResult approve() {
            return new ChargeResult(true, null);
        }

        public static ChargeResult decline(String reason) {
            return new ChargeResult(false, reason);
        }
    }
}
