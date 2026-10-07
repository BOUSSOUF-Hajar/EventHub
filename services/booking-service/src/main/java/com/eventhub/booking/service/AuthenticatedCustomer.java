package com.eventhub.booking.service;

import com.eventhub.booking.web.error.InvalidTokenException;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.UUID;

/**
 * Identite du client telle qu'elle sort du JWT Keycloak.
 *
 * Le "sub" Keycloak est un UUID : il sert d'identifiant client cote booking-service,
 * ce qui evite de dupliquer un referentiel utilisateur (Keycloak reste la source de
 * verite sur l'identite).
 */
public record AuthenticatedCustomer(UUID id, String email) {

    public static AuthenticatedCustomer from(Jwt jwt) {
        return new AuthenticatedCustomer(subjectOf(jwt), emailOf(jwt));
    }

    /**
     * Un "sub" non-UUID signifie un jeton emis par un autre fournisseur d'identite que
     * celui attendu : c'est un probleme d'authentification (401), pas un bug serveur.
     */
    private static UUID subjectOf(Jwt jwt) {
        String subject = jwt.getSubject();
        if (subject == null || subject.isBlank()) {
            throw new InvalidTokenException("le jeton ne porte pas de claim 'sub'");
        }
        try {
            return UUID.fromString(subject);
        } catch (IllegalArgumentException e) {
            throw new InvalidTokenException("le claim 'sub' n'est pas un UUID Keycloak : " + subject);
        }
    }

    /**
     * L'email alimente la notification de confirmation et la colonne est NOT NULL :
     * on degrade vers le username puis vers le sub plutot que de laisser passer un null
     * qui ferait echouer la reservation au moment du flush.
     */
    private static String emailOf(Jwt jwt) {
        String email = jwt.getClaimAsString("email");
        if (email == null || email.isBlank()) {
            email = jwt.getClaimAsString("preferred_username");
        }
        return email == null || email.isBlank() ? jwt.getSubject() : email;
    }
}
