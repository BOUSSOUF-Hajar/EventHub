package com.eventhub.gateway.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reponse de repli du circuit breaker : quand un service ne repond pas (ou que son circuit
 * est ouvert), le Gateway renvoie un 503 au format RFC 7807 plutot qu'une erreur 500 brute.
 */
@RestController
public class FallbackController {

    // Sans restriction de methode : le repli s'applique aussi bien a un GET qu'a un POST.
    // Le "detail" est affiche tel quel par le frontend.
    @RequestMapping("/fallback/{service}")
    public ProblemDetail fallback(@PathVariable String service) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "Le service est momentanément indisponible, réessayez dans un instant.");
        problem.setProperty("service", service);
        return problem;
    }
}
