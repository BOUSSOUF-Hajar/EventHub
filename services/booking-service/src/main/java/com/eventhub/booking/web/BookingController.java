package com.eventhub.booking.web;

import com.eventhub.booking.service.AuthenticatedCustomer;
import com.eventhub.booking.service.BookingService;
import com.eventhub.booking.web.dto.BookingResponse;
import com.eventhub.booking.web.dto.CreateBookingRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    private final BookingService bookingService;

    public BookingController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    /** BKG-1 : demande de reservation, creee en statut PENDING. */
    @PostMapping
    @PreAuthorize("hasRole('CUSTOMER') or hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public BookingResponse create(@Valid @RequestBody CreateBookingRequest request,
                                  JwtAuthenticationToken authentication) {
        AuthenticatedCustomer customer = customerOf(authentication);
        return BookingResponse.from(
                bookingService.create(request.eventId(), request.seatCount(), customer));
    }

    /** BKG-6 : la liste est filtree sur l'utilisateur du JWT, pas sur un parametre client. */
    @GetMapping("/me")
    public List<BookingResponse> findMine(JwtAuthenticationToken authentication) {
        return bookingService.findMine(customerOf(authentication).id())
                .stream()
                .map(BookingResponse::from)
                .toList();
    }

    @GetMapping("/{id}")
    public BookingResponse findOne(@PathVariable UUID id, JwtAuthenticationToken authentication) {
        return BookingResponse.from(bookingService.findOwned(id, customerOf(authentication).id()));
    }

    private AuthenticatedCustomer customerOf(JwtAuthenticationToken authentication) {
        return AuthenticatedCustomer.from((Jwt) authentication.getToken());
    }
}
