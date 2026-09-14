package com.keyloop.scheduler.application.port.in;

import com.keyloop.scheduler.application.port.in.command.BookAppointmentCommand;
import com.keyloop.scheduler.infrastructure.web.dto.response.AppointmentResponse;
import io.smallrye.mutiny.Uni;

/**
 * Input port — FR-02: Constrained Booking + FR-03: Idempotency.
 *
 * <p>Orchestrates the 5-phase booking flow (Section 7.2):
 * <ol>
 *   <li>Idempotency check (Redis)</li>
 *   <li>Distributed lock acquisition (Redis Lua)</li>
 *   <li>ACID transaction: INSERT appointment + outbox_event</li>
 *   <li>Lock release + idempotency cache (24h TTL)</li>
 *   <li>Return 201 Created</li>
 * </ol>
 */
public interface BookAppointmentUseCase {
    Uni<AppointmentResponse> execute(BookAppointmentCommand command);
}
