package com.eventhub.booking.web.error;

/**
 * Jeton syntaxiquement valide mais inexploitable (claims manquants ou mal formes).
 * Traduite en 401 : c'est le jeton qu'il faut corriger, pas la requete.
 */
public class InvalidTokenException extends RuntimeException {

    public InvalidTokenException(String message) {
        super("Jeton inexploitable : " + message);
    }
}
