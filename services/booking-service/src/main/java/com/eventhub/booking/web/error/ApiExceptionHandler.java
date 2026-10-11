package com.eventhub.booking.web.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * Traduction des erreurs metier en codes HTTP explicites (format RFC 7807).
 * Sans ce mapping, une rupture de stock remonterait en 500 et serait indiscernable
 * d'un bug cote serveur.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    // Le "detail" est affiche tel quel par le frontend : il est redige pour l'utilisateur,
    // sans identifiant technique. Le message de l'exception, lui, reste destine aux logs.
    @ExceptionHandler(NotEnoughSeatsException.class)
    public ProblemDetail handleNotEnoughSeats(NotEnoughSeatsException e) {
        log.info(e.getMessage());
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "Il ne reste plus assez de places pour cette demande.");
    }

    @ExceptionHandler(EventNotBookableException.class)
    public ProblemDetail handleEventNotBookable(EventNotBookableException e) {
        log.info(e.getMessage());
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "Cet événement n'est pas réservable : " + e.getReason() + ".");
    }

    @ExceptionHandler(EventNotFoundException.class)
    public ProblemDetail handleEventNotFound(EventNotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(BookingNotFoundException.class)
    public ProblemDetail handleBookingNotFound(BookingNotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(EventCatalogUnavailableException.class)
    public ProblemDetail handleCatalogUnavailable(EventCatalogUnavailableException e) {
        log.error("Service Evenements injoignable", e);
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "Le catalogue d'événements est momentanément indisponible, réessayez plus tard.");
    }

    @ExceptionHandler(SeatLockUnavailableException.class)
    public ProblemDetail handleSeatLockUnavailable(SeatLockUnavailableException e) {
        log.error("Verrou de disponibilite indisponible", e);
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "La disponibilité ne peut pas être vérifiée pour le moment, réessayez plus tard.");
    }

    @ExceptionHandler(InvalidTokenException.class)
    public ProblemDetail handleInvalidToken(InvalidTokenException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException e) {
        String details = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + " : " + error.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, details);
    }
}
