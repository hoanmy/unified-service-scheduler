package com.keyloop.scheduler.application.port.out;

import io.smallrye.mutiny.Uni;
import java.util.UUID;

/**
 * Output port for service type metadata lookup.
 * Used by the booking use case to determine appointment end time and skill requirements.
 */
public interface ServiceTypeRepositoryPort {
    Uni<com.keyloop.scheduler.infrastructure.web.dto.response.ServiceTypeDto> findById(UUID serviceTypeId);
}
