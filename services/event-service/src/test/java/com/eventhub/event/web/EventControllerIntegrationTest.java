package com.eventhub.event.web;

import com.eventhub.event.domain.Event;
import com.eventhub.event.repository.EventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Test d'integration : vrai PostgreSQL (Testcontainers) + vraie chaine de securite.
// Seul le JwtDecoder est remplace, pour ne pas dependre d'un Keycloak demarre :
// la conversion des roles "realm_access.roles" reste celle de SecurityConfig.
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class EventControllerIntegrationTest {

    private static final String ORGANIZER_TOKEN = "organizer-token";
    private static final String CUSTOMER_TOKEN = "customer-token";

    private static final String VALID_EVENT = """
            {
              "title": "Concert Jazz",
              "description": "Soiree jazz",
              "venue": "Rabat",
              "startsAt": "2027-01-15T20:00:00Z",
              "totalCapacity": 200,
              "remainingSeats": 200,
              "unitPrice": 35.00
            }
            """;

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EventRepository eventRepository;

    @MockBean
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void setUp() {
        eventRepository.deleteAll();
        when(jwtDecoder.decode(ORGANIZER_TOKEN)).thenReturn(jwtWithRole(ORGANIZER_TOKEN, "ORGANIZER"));
        when(jwtDecoder.decode(CUSTOMER_TOKEN)).thenReturn(jwtWithRole(CUSTOMER_TOKEN, "CUSTOMER"));
    }

    // EVT-1
    @Test
    void listIsPublic() throws Exception {
        savedEvent();

        mockMvc.perform(get("/api/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
    }

    // EVT-2
    @Test
    void detailIsPublicAndExposesCatalogueFields() throws Exception {
        Event event = savedEvent();

        mockMvc.perform(get("/api/events/{id}", event.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Concert Rock"))
                .andExpect(jsonPath("$.venue").value("Casablanca"))
                .andExpect(jsonPath("$.startsAt").value("2027-06-01T19:00:00Z"))
                .andExpect(jsonPath("$.remainingSeats").value(500))
                .andExpect(jsonPath("$.unitPrice").value(49.90));
    }

    @Test
    void detailOfUnknownEventReturns404() throws Exception {
        mockMvc.perform(get("/api/events/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    // EVT-3
    @Test
    void createWithoutTokenReturns401() throws Exception {
        mockMvc.perform(post("/api/events").contentType(MediaType.APPLICATION_JSON).content(VALID_EVENT))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createAsCustomerReturns403() throws Exception {
        mockMvc.perform(post("/api/events")
                        .header("Authorization", "Bearer " + CUSTOMER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_EVENT))
                .andExpect(status().isForbidden());

        assertThat(eventRepository.count()).isZero();
    }

    @Test
    void createAsOrganizerReturns201AndPersists() throws Exception {
        mockMvc.perform(post("/api/events")
                        .header("Authorization", "Bearer " + ORGANIZER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_EVENT))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.title").value("Concert Jazz"))
                .andExpect(jsonPath("$.unitPrice").value(35.00));

        assertThat(eventRepository.count()).isEqualTo(1);
    }

    @Test
    void createWithBlankTitleReturns400() throws Exception {
        mockMvc.perform(post("/api/events")
                        .header("Authorization", "Bearer " + ORGANIZER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\": \"\", \"venue\": \"Rabat\"}"))
                .andExpect(status().isBadRequest());
    }

    // EVT-4
    @Test
    void deleteAsCustomerReturns403() throws Exception {
        Event event = savedEvent();

        mockMvc.perform(delete("/api/events/{id}", event.getId())
                        .header("Authorization", "Bearer " + CUSTOMER_TOKEN))
                .andExpect(status().isForbidden());

        assertThat(eventRepository.existsById(event.getId())).isTrue();
    }

    @Test
    void deleteAsOrganizerReturns204AndRemoves() throws Exception {
        Event event = savedEvent();

        mockMvc.perform(delete("/api/events/{id}", event.getId())
                        .header("Authorization", "Bearer " + ORGANIZER_TOKEN))
                .andExpect(status().isNoContent());

        assertThat(eventRepository.existsById(event.getId())).isFalse();
    }

    @Test
    void healthIsPublic() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    private Event savedEvent() {
        return eventRepository.save(new Event("Concert Rock", "Groupes locaux", "Casablanca",
                Instant.parse("2027-06-01T19:00:00Z"), 500, new BigDecimal("49.90")));
    }

    private static Jwt jwtWithRole(String tokenValue, String role) {
        return Jwt.withTokenValue(tokenValue)
                .header("alg", "none")
                .subject("test-user")
                .claim("realm_access", Map.of("roles", List.of(role)))
                .build();
    }
}
