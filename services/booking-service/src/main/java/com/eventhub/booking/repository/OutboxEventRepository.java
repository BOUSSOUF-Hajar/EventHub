package com.eventhub.booking.repository;

import com.eventhub.booking.domain.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Recupere un lot d'evenements non encore publies.
     *
     * FOR UPDATE SKIP LOCKED : si plusieurs instances du service tournent en
     * parallele, chacune verrouille son propre lot et ignore les lignes deja
     * prises par une autre, au lieu de bloquer ou de publier deux fois.
     */
    @Query(value = """
            SELECT * FROM outbox_events
            WHERE published_at IS NULL
            ORDER BY created_at
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> lockUnpublishedBatch(@Param("batchSize") int batchSize);

    List<OutboxEvent> findByAggregateIdOrderByCreatedAtAsc(UUID aggregateId);
}
