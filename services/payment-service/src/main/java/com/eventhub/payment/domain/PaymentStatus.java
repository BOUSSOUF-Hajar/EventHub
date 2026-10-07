package com.eventhub.payment.domain;

/**
 * Un paiement est enregistre une fois son issue connue : il n'existe donc pas d'etat
 * intermediaire a reprendre apres un crash, la relivraison du message s'en charge.
 */
public enum PaymentStatus {
    SUCCEEDED,
    FAILED
}
