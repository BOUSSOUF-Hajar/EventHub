package com.eventhub.payment.repository;

import com.eventhub.payment.domain.Payment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    boolean existsByBookingId(UUID bookingId);

    Optional<Payment> findByBookingId(UUID bookingId);
}
