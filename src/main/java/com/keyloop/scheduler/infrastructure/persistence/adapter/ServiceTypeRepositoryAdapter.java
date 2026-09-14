package com.keyloop.scheduler.infrastructure.persistence.adapter;

import com.keyloop.scheduler.application.port.out.ServiceTypeRepositoryPort;
import com.keyloop.scheduler.infrastructure.persistence.entity.ServiceTypeJpaEntity;
import com.keyloop.scheduler.infrastructure.web.dto.response.ServiceTypeDto;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.NotFoundException;

import java.util.UUID;

/** Adapter for service type metadata lookups. */
@ApplicationScoped
public class ServiceTypeRepositoryAdapter implements ServiceTypeRepositoryPort {

    @Override
    public Uni<ServiceTypeDto> findById(UUID serviceTypeId) {
        return ServiceTypeJpaEntity.<ServiceTypeJpaEntity>findById(serviceTypeId)
            .onItem().ifNull().failWith(() -> new NotFoundException("ServiceType not found: " + serviceTypeId))
            .map(e -> new ServiceTypeDto(
                e.getId(), e.getDealershipId(), e.getName(),
                e.getDurationMinutes(), e.getRequiredSkillLevel()
            ));
    }
}
