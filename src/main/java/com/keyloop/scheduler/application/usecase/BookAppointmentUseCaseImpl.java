package com.keyloop.scheduler.application.usecase;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keyloop.scheduler.application.port.in.BookAppointmentUseCase;
import com.keyloop.scheduler.application.port.in.command.BookAppointmentCommand;
import com.keyloop.scheduler.application.port.out.AppointmentRepositoryPort;
import com.keyloop.scheduler.application.port.out.DistributedLockPort;
import com.keyloop.scheduler.application.port.out.IdempotencyPort;
import com.keyloop.scheduler.application.port.out.OutboxEventRepositoryPort;
import com.keyloop.scheduler.application.port.out.ServiceTypeRepositoryPort;
import com.keyloop.scheduler.domain.entity.Appointment;
import com.keyloop.scheduler.domain.entity.OutboxEvent;
import com.keyloop.scheduler.domain.exception.DoubleBookingException;
import com.keyloop.scheduler.domain.exception.ResourceLockedException;
import com.keyloop.scheduler.domain.valueobject.EventType;
import com.keyloop.scheduler.domain.valueobject.TimeSlot;
import com.keyloop.scheduler.infrastructure.web.dto.response.AppointmentResponse;
import com.keyloop.scheduler.infrastructure.web.dto.response.ServiceTypeDto;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.format.DateTimeFormatter;

/**
 * FR-02 + FR-03: Constrained Booking with Idempotency.
 *
 * <p>Implements the 5-phase booking flow from Section 7.2 of the system design:
 * <ol>
 *   <li><b>Phase 1 — Idempotency Check:</b> If idempotency key exists in Redis,
 *       return cached 201 response immediately (< 5ms).</li>
 *   <li><b>Phase 2 — Distributed Locking:</b> Acquire Redis locks for technician
 *       and bay slots. Returns 409 in < 5ms if lock held by competing request.
 *       Deflects 99.99% of Thundering Herd contention from the database.</li>
 *   <li><b>Phase 3 — ACID Transaction:</b> INSERT into {@code appointment} and
 *       {@code outbox_event} atomically. PostgreSQL GiST exclusion constraints
 *       are the absolute final failsafe against race conditions.</li>
 *   <li><b>Phase 4 — Cleanup:</b> Release Redis locks, cache response under
 *       idempotency key (24h TTL), return 201 Created.</li>
 *   <li><b>Phase 5 — Async (out-of-band):</b> Debezium CDC tails WAL → Kafka →
 *       Search Sync Worker + Notification Worker (eventual consistency).</li>
 * </ol>
 */
@ApplicationScoped
public class BookAppointmentUseCaseImpl implements BookAppointmentUseCase {

    private static final Logger LOG = Logger.getLogger(BookAppointmentUseCaseImpl.class);

    @Inject AppointmentRepositoryPort appointmentRepository;
    @Inject OutboxEventRepositoryPort outboxRepository;
    @Inject DistributedLockPort lockPort;
    @Inject IdempotencyPort idempotencyPort;
    @Inject ServiceTypeRepositoryPort serviceTypeRepository;
    @Inject ObjectMapper objectMapper;

    @ConfigProperty(name = "scheduler.lock.ttl-seconds", defaultValue = "5")
    int lockTtlSeconds;

    @ConfigProperty(name = "scheduler.idempotency.ttl-seconds", defaultValue = "86400")
    int idempotencyTtlSeconds;

    @Override
    public Uni<AppointmentResponse> execute(BookAppointmentCommand command) {
        LOG.debugf("Booking appointment: idempotencyKey=%s, dealer=%s, tech=%s, bay=%s, start=%s",
            command.idempotencyKey(), command.dealershipId(), command.technicianId(),
            command.serviceBayId(), command.startTime());

        // ── Phase 1: Idempotency Check ─────────────────────────────────────
        return idempotencyPort.findCachedResponse(command.idempotencyKey())
            .flatMap(cached -> {
                if (cached.isPresent()) {
                    LOG.debugf("Idempotency cache hit for key: %s", command.idempotencyKey());
                    return Uni.createFrom().item(deserializeResponse(cached.get()));
                }
                return executeBookingFlow(command);
            });
    }

    private Uni<AppointmentResponse> executeBookingFlow(BookAppointmentCommand command) {
        // Load service type for duration + skill level validation
        return serviceTypeRepository.findById(command.serviceTypeId())
            .flatMap(serviceType -> {
                // Compute time slot: EndTime = StartTime + DurationMinutes
                TimeSlot timeSlot = TimeSlot.of(command.startTime(), serviceType.durationMinutes());

                // ── Phase 2: Distributed Lock Acquisition ─────────────────
                String techLockKey = buildTechLockKey(command, timeSlot);
                String bayLockKey  = buildBayLockKey(command, timeSlot);

                return acquireBothLocks(techLockKey, bayLockKey)
                    .flatMap(__ -> executeAcidTransaction(command, serviceType, timeSlot))
                    .flatMap(response -> cleanupAndCache(techLockKey, bayLockKey, command.idempotencyKey(), response))
                    .onFailure(ex -> !(ex instanceof ResourceLockedException) && !(ex instanceof DoubleBookingException))
                    .invoke(ex -> {
                        // Release locks on unexpected errors to prevent lock leaks
                        lockPort.release(techLockKey).subscribe().with(__ -> {}, err -> {});
                        lockPort.release(bayLockKey).subscribe().with(__ -> {}, err -> {});
                    });
            });
    }

    private Uni<Void> acquireBothLocks(String techLockKey, String bayLockKey) {
        return lockPort.tryAcquire(techLockKey, lockTtlSeconds)
            .flatMap(techAcquired -> {
                if (!techAcquired) {
                    LOG.debugf("Tech lock contention: %s", techLockKey);
                    throw new ResourceLockedException(techLockKey);
                }
                return lockPort.tryAcquire(bayLockKey, lockTtlSeconds);
            })
            .flatMap(bayAcquired -> {
                if (!bayAcquired) {
                    // Release tech lock since bay is unavailable
                    return lockPort.release(techLockKey)
                        .flatMap(__ -> Uni.createFrom().failure(new ResourceLockedException(bayLockKey)));
                }
                return Uni.createFrom().voidItem();
            });
    }

    private Uni<AppointmentResponse> executeAcidTransaction(
        BookAppointmentCommand command, ServiceTypeDto serviceType, TimeSlot timeSlot) {

        // ── Phase 3: ACID Transaction ──────────────────────────────────────
        // Both inserts run atomically. If PG exclusion constraint fires,
        // transaction rolls back and DoubleBookingException propagates.
        Appointment appointment = Appointment.create(
            command.dealershipId(), command.customerId(), command.vehicleId(),
            command.serviceTypeId(), command.technicianId(), command.serviceBayId(), timeSlot
        );

        String payload = buildEventPayload(appointment, serviceType);
        OutboxEvent outboxEvent = OutboxEvent.forAppointment(
            appointment, EventType.APPOINTMENT_BOOKED.getValue(), payload);

        return appointmentRepository.save(appointment)
            .flatMap(saved -> outboxRepository.save(outboxEvent).map(__ -> saved))
            .map(saved -> toResponse(saved, timeSlot))
            .onFailure(ex -> ex.getMessage() != null
                && (ex.getMessage().contains("exclude_technician_overlapping_slots")
                    || ex.getMessage().contains("exclude_service_bay_overlapping_slots")))
            .transform(ex -> new DoubleBookingException(command.technicianId(), "TECHNICIAN/BAY"));
    }

    private Uni<AppointmentResponse> cleanupAndCache(
        String techLockKey, String bayLockKey, String idempotencyKey, AppointmentResponse response) {

        // ── Phase 4: Release locks + cache idempotency response ───────────
        String jsonResponse = serializeResponse(response);
        return lockPort.release(techLockKey)
            .flatMap(__ -> lockPort.release(bayLockKey))
            .flatMap(__ -> idempotencyPort.cacheResponse(idempotencyKey, jsonResponse, idempotencyTtlSeconds))
            .map(__ -> response);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String buildTechLockKey(BookAppointmentCommand cmd, TimeSlot slot) {
        return "lock:tech:%s:%s".formatted(cmd.technicianId(),
            slot.start().format(DateTimeFormatter.ofPattern("yyyyMMddHHmm")));
    }

    private static String buildBayLockKey(BookAppointmentCommand cmd, TimeSlot slot) {
        return "lock:bay:%s:%s".formatted(cmd.serviceBayId(),
            slot.start().format(DateTimeFormatter.ofPattern("yyyyMMddHHmm")));
    }

    private AppointmentResponse toResponse(Appointment a, TimeSlot slot) {
        return new AppointmentResponse(
            a.getId(), a.getStatus(), a.getDealershipId(), a.getCustomerId(),
            a.getVehicleId(), a.getServiceTypeId(), a.getTechnicianId(), a.getServiceBayId(),
            slot.start(), slot.end(), a.getCreatedAt()
        );
    }

    private String buildEventPayload(Appointment appointment, ServiceTypeDto serviceType) {
        try {
            var payload = new java.util.HashMap<String, Object>();
            payload.put("appointmentId", appointment.getId().toString());
            payload.put("dealershipId",  appointment.getDealershipId().toString());
            payload.put("technicianId",  appointment.getTechnicianId().toString());
            payload.put("serviceBayId",  appointment.getServiceBayId().toString());
            payload.put("startTime",     appointment.getTimeSlot().start().toString());
            payload.put("endTime",       appointment.getTimeSlot().end().toString());
            payload.put("status",        appointment.getStatus().name());
            payload.put("serviceType",   serviceType.name());
            payload.put("durationMinutes", serviceType.durationMinutes());
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize outbox event payload", e);
        }
    }

    private String serializeResponse(AppointmentResponse response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize appointment response", e);
        }
    }

    private AppointmentResponse deserializeResponse(String json) {
        try {
            return objectMapper.readValue(json, AppointmentResponse.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize cached appointment response", e);
        }
    }
}
