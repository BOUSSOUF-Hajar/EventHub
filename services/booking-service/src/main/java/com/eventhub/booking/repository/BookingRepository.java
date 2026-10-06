package com.eventhub.booking.repository;

import com.eventhub.booking.domain.Booking;
import com.eventhub.booking.domain.BookingStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface BookingRepository extends JpaRepository<Booking, UUID> {

    List<Booking> findByCustomerIdOrderByCreatedAtDesc(UUID customerId);

    List<Booking> findByStatusInAndCreatedAtBefore(List<BookingStatus> statuses, Instant threshold);
}
