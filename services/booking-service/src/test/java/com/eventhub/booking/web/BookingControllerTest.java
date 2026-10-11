package com.eventhub.booking.web;

import com.eventhub.booking.config.SecurityConfig;
import com.eventhub.booking.domain.Booking;
import com.eventhub.booking.service.AuthenticatedCustomer;
import com.eventhub.booking.service.BookingService;
import com.eventhub.booking.support.TestSecurityConfig;
import com.eventhub.booking.web.error.NotEnoughSeatsException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exigence non fonctionnelle "Securite" : aucun endpoint d'ecriture accessible sans
 * JWT valide, et roles verifies dans le service lui-meme. Ces cas verifient la regle
 * au niveau HTTP, la ou un client la rencontrerait.
 */
@WebMvcTest(BookingController.class)
@Import({SecurityConfig.class, TestSecurityConfig.class})
class BookingControllerTest {

    private static final UUID EVENT_ID = UUID.randomUUID();
    private static final UUID CUSTOMER_ID = UUID.randomUUID();
    private static final String VALID_BODY = """
            {"eventId":"%s","seatCount":2}
            """.formatted(EVENT_ID);

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private BookingService bookingService;

    @Test
    @DisplayName("sans jeton, la creation de reservation est refusee (401)")
    void anonymousCannotBook() throws Exception {
        mockMvc.perform(post("/api/bookings").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isUnauthorized());

        then(bookingService).should(never()).create(any(), anyInt(), any());
    }

    @Test
    @DisplayName("un jeton sans le role CUSTOMER est refuse (403)")
    void tokenWithoutCustomerRoleIsForbidden() throws Exception {
        mockMvc.perform(post("/api/bookings")
                        .with(jwt().jwt(customerToken()).authorities(List.of()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isForbidden());

        then(bookingService).should(never()).create(any(), anyInt(), any());
    }

    @Test
    @DisplayName("un CUSTOMER authentifie obtient une reservation en 201")
    void customerCanBook() throws Exception {
        Booking booking = new Booking(EVENT_ID, CUSTOMER_ID, "alice@example.com", 2, new BigDecimal("50.00"));
        given(bookingService.create(eq(EVENT_ID), eq(2), any(AuthenticatedCustomer.class))).willReturn(booking);

        mockMvc.perform(post("/api/bookings")
                        .with(customerJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.eventId").value(EVENT_ID.toString()))
                .andExpect(jsonPath("$.seatCount").value(2))
                .andExpect(jsonPath("$.status").value("PENDING"))
                // le client ne doit pas pouvoir deduire l'identite interne d'autrui
                .andExpect(jsonPath("$.customerId").doesNotExist());
    }

    @Test
    @DisplayName("l'identite du client vient du JWT, pas du corps de la requete")
    void customerIdentityIsTakenFromTheToken() throws Exception {
        Booking booking = new Booking(EVENT_ID, CUSTOMER_ID, "alice@example.com", 2, new BigDecimal("50.00"));
        given(bookingService.create(any(), anyInt(), any(AuthenticatedCustomer.class))).willReturn(booking);

        // le corps tente d'usurper une autre identite : elle doit etre ignoree
        String spoofed = """
                {"eventId":"%s","seatCount":2,"customerId":"%s"}
                """.formatted(EVENT_ID, UUID.randomUUID());

        mockMvc.perform(post("/api/bookings")
                        .with(customerJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(spoofed))
                .andExpect(status().isCreated());

        var captor = org.mockito.ArgumentCaptor.forClass(AuthenticatedCustomer.class);
        then(bookingService).should().create(any(), anyInt(), captor.capture());
        assertThat(captor.getValue().id()).isEqualTo(CUSTOMER_ID);
        assertThat(captor.getValue().email()).isEqualTo("alice@example.com");
    }

    @Test
    @DisplayName("un jeton dont le 'sub' n'est pas un UUID est rejete en 401, pas en 500")
    void tokenWithNonUuidSubjectIsUnauthorized() throws Exception {
        mockMvc.perform(post("/api/bookings")
                        .with(jwt().jwt(builder -> builder
                                        .subject("pas-un-uuid")
                                        .claim("email", "alice@example.com"))
                                .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_CUSTOMER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isUnauthorized());

        then(bookingService).should(never()).create(any(), anyInt(), any());
    }

    @Test
    @DisplayName("sans claim email, l'identite retombe sur le username plutot que de casser")
    void missingEmailFallsBackToUsername() throws Exception {
        Booking booking = new Booking(EVENT_ID, CUSTOMER_ID, "alice.customer", 2, new BigDecimal("50.00"));
        given(bookingService.create(any(), anyInt(), any(AuthenticatedCustomer.class))).willReturn(booking);

        mockMvc.perform(post("/api/bookings")
                        .with(jwt().jwt(builder -> builder
                                        .subject(CUSTOMER_ID.toString())
                                        .claim("preferred_username", "alice.customer")
                                        .claim("realm_access", Map.of("roles", List.of("CUSTOMER"))))
                                .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_CUSTOMER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isCreated());

        var captor = org.mockito.ArgumentCaptor.forClass(AuthenticatedCustomer.class);
        then(bookingService).should().create(any(), anyInt(), captor.capture());
        assertThat(captor.getValue().email()).isEqualTo("alice.customer");
    }

    @Test
    @DisplayName("un nombre de places invalide est rejete en 400")
    void invalidSeatCountIsRejected() throws Exception {
        mockMvc.perform(post("/api/bookings")
                        .with(customerJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"eventId":"%s","seatCount":0}
                                """.formatted(EVENT_ID)))
                .andExpect(status().isBadRequest());

        then(bookingService).should(never()).create(any(), anyInt(), any());
    }

    @Test
    @DisplayName("une rupture de stock remonte en 409, pas en 500")
    void soldOutEventReturnsConflict() throws Exception {
        given(bookingService.create(any(), anyInt(), any()))
                .willThrow(new NotEnoughSeatsException(EVENT_ID, 2));

        mockMvc.perform(post("/api/bookings")
                        .with(customerJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isConflict())
                // message affiche tel quel a l'utilisateur : lisible, sans identifiant technique
                .andExpect(jsonPath("$.detail").value("Il ne reste plus assez de places pour cette demande."));
    }

    @Test
    @DisplayName("GET /api/bookings/me utilise l'identifiant du jeton")
    void myBookingsAreScopedToTheToken() throws Exception {
        given(bookingService.findMine(CUSTOMER_ID)).willReturn(List.of(
                new Booking(EVENT_ID, CUSTOMER_ID, "alice@example.com", 1, new BigDecimal("25.00"))));

        mockMvc.perform(get("/api/bookings/me").with(customerJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        then(bookingService).should().findMine(CUSTOMER_ID);
    }

    @Test
    @DisplayName("GET /api/bookings/me exige un jeton")
    void myBookingsRequireAuthentication() throws Exception {
        mockMvc.perform(get("/api/bookings/me"))
                .andExpect(status().isUnauthorized());
    }

    private static Consumer<Jwt.Builder> customerToken() {
        return builder -> builder
                .subject(CUSTOMER_ID.toString())
                .claim("email", "alice@example.com")
                .claim("realm_access", Map.of("roles", List.of("CUSTOMER")));
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor customerJwt() {
        return jwt().jwt(customerToken())
                .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_CUSTOMER"));
    }
}
