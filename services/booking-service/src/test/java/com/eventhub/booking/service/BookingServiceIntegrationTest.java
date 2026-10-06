package com.eventhub.booking.service;

import com.eventhub.booking.client.EventCatalogClient;
import com.eventhub.booking.client.EventSummary;
import com.eventhub.booking.domain.Booking;
import com.eventhub.booking.domain.BookingStatus;
import com.eventhub.booking.domain.OutboxEvent;
import com.eventhub.booking.repository.BookingRepository;
import com.eventhub.booking.repository.OutboxEventRepository;
import com.eventhub.booking.support.AbstractIntegrationTest;
import com.eventhub.booking.web.error.NotEnoughSeatsException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

class BookingServiceIntegrationTest extends AbstractIntegrationTest {

    private static final BigDecimal UNIT_PRICE = new BigDecimal("25.00");

    @Autowired
    private BookingService bookingService;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * event-service est mocke : ce test porte sur la logique de reservation, pas sur
     * l'integration HTTP avec le catalogue. Le cahier des charges interdit le couplage
     * fort entre services, il serait incoherent d'en exiger un pour tester.
     */
    @MockBean
    private EventCatalogClient eventCatalogClient;

    private final AuthenticatedCustomer alice =
            new AuthenticatedCustomer(UUID.randomUUID(), "alice@example.com");

    @BeforeEach
    void cleanDatabase() {
        outboxEventRepository.deleteAll();
        bookingRepository.deleteAll();
    }

    @Test
    @DisplayName("la reservation et sa ligne d'outbox sont ecrites ensemble")
    void createWritesBookingAndOutboxRow() throws Exception {
        UUID eventId = givenEventWithCapacity(50);

        Booking booking = bookingService.create(eventId, 2, alice);

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.PENDING);
        assertThat(booking.getTotalAmount()).isEqualByComparingTo("50.00");
        assertThat(bookingRepository.findById(booking.getId())).isPresent();

        List<OutboxEvent> outbox = outboxEventRepository.findByAggregateIdOrderByCreatedAtAsc(booking.getId());
        assertThat(outbox).singleElement().satisfies(event -> {
            assertThat(event.getEventType()).isEqualTo("booking.requested");
            assertThat(event.getPublishedAt()).as("pas encore publie").isNull();
        });

        JsonNode payload = objectMapper.readTree(outbox.get(0).getPayload());
        assertThat(payload.get("messageId").asText()).isEqualTo(outbox.get(0).getId().toString());
        assertThat(payload.get("bookingId").asText()).isEqualTo(booking.getId().toString());
        assertThat(payload.get("customerEmail").asText()).isEqualTo("alice@example.com");
        assertThat(payload.get("seatCount").asInt()).isEqualTo(2);
        assertThat(payload.get("amount").decimalValue()).isEqualByComparingTo("50.00");
    }

    @Test
    @DisplayName("BKG-2 : deux reservations simultanees de la derniere place, une seule aboutit")
    void twoConcurrentRequestsOnTheLastSeatProduceASingleBooking() throws Exception {
        UUID eventId = givenEventWithCapacity(1);

        AtomicInteger rejected = new AtomicInteger();
        long created = runConcurrently(2, () -> {
            try {
                bookingService.create(eventId, 1, alice);
                return true;
            } catch (NotEnoughSeatsException e) {
                rejected.incrementAndGet();
                return false;
            }
        });

        assertThat(created).isEqualTo(1);
        assertThat(rejected.get()).isEqualTo(1);
        assertThat(bookingRepository.findAll()).hasSize(1);
        // une seule reservation => un seul evenement a publier, sinon payment-service
        // debiterait un client qui n'a pas de place
        assertThat(outboxEventRepository.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("20 clients pour 5 places : exactement 5 reservations, jamais 6")
    void heavyContentionNeverOversells() throws Exception {
        UUID eventId = givenEventWithCapacity(5);

        long created = runConcurrently(20, () -> {
            try {
                bookingService.create(eventId, 1, alice);
                return true;
            } catch (NotEnoughSeatsException e) {
                return false;
            }
        });

        assertThat(created).isEqualTo(5);
        assertThat(bookingRepository.findAll()).hasSize(5);
    }

    @Test
    @DisplayName("une demande superieure aux places restantes est refusee en 409")
    void requestBeyondRemainingCapacityIsRejected() {
        UUID eventId = givenEventWithCapacity(3);
        bookingService.create(eventId, 2, alice);

        assertThatThrownBy(() -> bookingService.create(eventId, 2, alice))
                .isInstanceOf(NotEnoughSeatsException.class);

        assertThat(bookingRepository.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("BKG-6 : /me ne renvoie que les reservations du client courant")
    void findMineIsScopedToTheCurrentCustomer() {
        UUID eventId = givenEventWithCapacity(50);
        AuthenticatedCustomer bob = new AuthenticatedCustomer(UUID.randomUUID(), "bob@example.com");

        bookingService.create(eventId, 1, alice);
        bookingService.create(eventId, 1, alice);
        bookingService.create(eventId, 1, bob);

        assertThat(bookingService.findMine(alice.id())).hasSize(2);
        assertThat(bookingService.findMine(bob.id())).hasSize(1);
    }

    private UUID givenEventWithCapacity(int capacity) {
        UUID eventId = UUID.randomUUID();
        given(eventCatalogClient.findById(any(UUID.class)))
                .willReturn(new EventSummary(eventId, capacity, UNIT_PRICE));
        return eventId;
    }

    private long runConcurrently(int threads, Callable<Boolean> action) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier startLine = new CyclicBarrier(threads);
        try {
            List<Future<Boolean>> results = pool.invokeAll(IntStream.range(0, threads)
                    .<Callable<Boolean>>mapToObj(i -> () -> {
                        startLine.await(20, TimeUnit.SECONDS);
                        return action.call();
                    })
                    .toList());

            long successes = 0;
            for (Future<Boolean> result : results) {
                if (Boolean.TRUE.equals(result.get(20, TimeUnit.SECONDS))) {
                    successes++;
                }
            }
            return successes;
        } finally {
            pool.shutdownNow();
        }
    }
}
