package com.keyloop.scheduler.application.usecase;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keyloop.scheduler.application.port.in.CancelAppointmentUseCase;
import com.keyloop.scheduler.application.port.in.command.CancelAppointmentCommand;
import com.keyloop.scheduler.application.port.out.AppointmentRepositoryPort;
import com.keyloop.scheduler.application.port.out.OutboxEventRepositoryPort;
import com.keyloop.scheduler.domain.entity.OutboxEvent;
import com.keyloop.scheduler.domain.valueobject.AppointmentStatus;
import com.keyloop.scheduler.domain.valueobject.EventType;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Map;

/**
 * FR-04: Cancellation & Resource Release.
 *
 * <p>Transitions appointment to CANCELLED status and atomically inserts
 * an {@code AppointmentCancelled} outbox event. Debezium CDC propagates
 * the cancellation event through Kafka to the Search Sync Worker, which
 * restores the slot in Elasticsearch within the 200ms eventual consistency window.
 *
 * <p>Resources (technician time block + bay time block) are logically released
 * because the GiST exclusion constraints only apply to {@code status = 'CONFIRMED'}.
 */
@ApplicationScoped
public class CancelAppointmentUseCaseImpl implements CancelAppointmentUseCase {

    private static final Logger LOG = Logger.getLogger(CancelAppointmentUseCaseImpl.class);

    @Inject AppointmentRepositoryPort appointmentRepository;
    @Inject OutboxEventRepositoryPort outboxRepository;
    @Inject ObjectMapper objectMapper;

    @Override
    public Uni<Void> execute(CancelAppointmentCommand command) {
        LOG.debugf("Cancelling appointment: id=%s, reason=%s", command.appointmentId(), command.reason());

        return appointmentRepository.findById(command.appointmentId())
            .flatMap(appointment -> {
                // Validate state transition (throws InvalidStatusTransitionException if invalid)
                appointment.cancel();

                String payload = buildCancellationPayload(appointment.getId(), command.reason());
                OutboxEvent outboxEvent = OutboxEvent.forAppointment(
                    appointment, EventType.APPOINTMENT_CANCELLED.getValue(), payload);

                // Atomic: update status + insert outbox event in same transaction
                return appointmentRepository.updateStatus(command.appointmentId(), AppointmentStatus.CANCELLED)
                    .flatMap(__ -> outboxRepository.save(outboxEvent))
                    .replaceWithVoid();
            });
    }

    private String buildCancellationPayload(java.util.UUID appointmentId, String reason) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                "appointmentId", appointmentId.toString(),
                "status", "CANCELLED",
                "reason", reason
            ));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize cancellation event payload", e);
        }
    }
}
