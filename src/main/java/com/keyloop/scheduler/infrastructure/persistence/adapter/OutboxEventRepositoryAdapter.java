package com.keyloop.scheduler.infrastructure.persistence.adapter;

import com.keyloop.scheduler.application.port.out.OutboxEventRepositoryPort;
import com.keyloop.scheduler.domain.entity.OutboxEvent;
import com.keyloop.scheduler.infrastructure.persistence.entity.OutboxEventJpaEntity;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.OffsetDateTime;

/** Adapter for persisting outbox events within the current ACID transaction. */
@ApplicationScoped
public class OutboxEventRepositoryAdapter implements OutboxEventRepositoryPort {

    @Override
    @WithTransaction
    public Uni<OutboxEvent> save(OutboxEvent event) {
        OutboxEventJpaEntity entity = OutboxEventJpaEntity.builder()
            .id(event.id())
            .aggregateType(event.aggregateType())
            .aggregateId(event.aggregateId())
            .eventType(event.eventType())
            .dealershipId(event.dealershipId())
            .payload(event.payload())
            .createdAt(OffsetDateTime.now())
            .build();

        return entity.persist().map(__ -> event);
    }
}
