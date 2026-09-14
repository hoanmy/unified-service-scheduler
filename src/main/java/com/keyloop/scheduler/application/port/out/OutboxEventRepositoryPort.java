package com.keyloop.scheduler.application.port.out;

import com.keyloop.scheduler.domain.entity.OutboxEvent;
import io.smallrye.mutiny.Uni;

/** Output port for writing outbox events within the same ACID transaction. */
public interface OutboxEventRepositoryPort {
    Uni<OutboxEvent> save(OutboxEvent event);
}
