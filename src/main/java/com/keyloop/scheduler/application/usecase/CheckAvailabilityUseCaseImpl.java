package com.keyloop.scheduler.application.usecase;

import com.keyloop.scheduler.application.port.in.CheckAvailabilityUseCase;
import com.keyloop.scheduler.application.port.in.query.AvailabilityQuery;
import com.keyloop.scheduler.application.port.out.AvailabilitySearchPort;
import com.keyloop.scheduler.application.port.out.ServiceTypeRepositoryPort;
import com.keyloop.scheduler.infrastructure.web.dto.response.AvailabilityResponse;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Fallback;
import org.eclipse.microprofile.faulttolerance.Timeout;
import org.jboss.logging.Logger;

import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * FR-01: Availability Search — CQRS Read Path.
 *
 * <p>Query flow (Section 7.1):
 * <ol>
 *   <li>Resolve service type metadata (duration + required skill level)</li>
 *   <li>Query Elasticsearch with multi-dimensional bool filter</li>
 *   <li>Map results to AvailabilityResponse DTO</li>
 * </ol>
 *
 * <p>Resilience: Circuit Breaker (Resilience4j via SmallRye Fault Tolerance)
 * opens on sustained Elasticsearch failures, preventing thread pool exhaustion.
 * Fallback returns a graceful degraded response.
 */
@ApplicationScoped
public class CheckAvailabilityUseCaseImpl implements CheckAvailabilityUseCase {

    private static final Logger LOG = Logger.getLogger(CheckAvailabilityUseCaseImpl.class);

    @Inject AvailabilitySearchPort searchPort;
    @Inject ServiceTypeRepositoryPort serviceTypeRepository;

    @Override
    @Timeout(value = 500, unit = ChronoUnit.MILLIS)
    @CircuitBreaker(
        requestVolumeThreshold = 20,
        failureRatio = 0.5,
        delay = 10,
        delayUnit = ChronoUnit.SECONDS,
        successThreshold = 3
    )
    @Fallback(fallbackMethod = "fallbackAvailability")
    public Uni<AvailabilityResponse> execute(AvailabilityQuery query) {
        LOG.debugf("Checking availability: dealershipId=%s, serviceTypeId=%s, start=%s, end=%s",
            query.dealershipId(), query.serviceTypeId(), query.startDate(), query.endDate());

        return serviceTypeRepository.findById(query.serviceTypeId())
            .flatMap(serviceType ->
                searchPort.findAvailableSlots(query, serviceType.durationMinutes(), serviceType.requiredSkillLevel())
                    .map(slots -> new AvailabilityResponse(
                        query.dealershipId(),
                        query.serviceTypeId(),
                        serviceType.durationMinutes(),
                        slots
                    ))
            );
    }

    /**
     * Circuit breaker fallback — returns empty availability with service degraded indicator.
     * Prevents cascading failure when Elasticsearch is unavailable.
     */
    public Uni<AvailabilityResponse> fallbackAvailability(AvailabilityQuery query) {
        LOG.warnf("Circuit breaker open — returning degraded availability for dealershipId=%s", query.dealershipId());
        return Uni.createFrom().item(
            new AvailabilityResponse(query.dealershipId(), query.serviceTypeId(), 0, List.of())
        );
    }
}
