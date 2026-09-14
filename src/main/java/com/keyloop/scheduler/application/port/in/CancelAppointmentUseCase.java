package com.keyloop.scheduler.application.port.in;

import com.keyloop.scheduler.application.port.in.command.CancelAppointmentCommand;
import io.smallrye.mutiny.Uni;

/**
 * Input port — FR-04: Cancellation & Resource Release.
 *
 * <p>Transitions appointment to CANCELLED, writes AppointmentCancelled outbox event.
 * Downstream CDC → Kafka → Search Sync Worker restores the slot in Elasticsearch
 * within the 200ms eventual consistency window.
 */
public interface CancelAppointmentUseCase {
    Uni<Void> execute(CancelAppointmentCommand command);
}
