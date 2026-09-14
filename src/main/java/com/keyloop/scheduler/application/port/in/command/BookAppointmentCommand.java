package com.keyloop.scheduler.application.port.in.command;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Command object for the FR-02 Booking use case.
 *
 * <p>Encapsulates all required inputs to create an appointment.
 * The Idempotency-Key from the HTTP header is included here to be
 * checked against Redis (Phase 1 of the 5-phase booking flow).
 */
public record BookAppointmentCommand(
    @NotNull String idempotencyKey,
    @NotNull UUID customerId,
    @NotNull UUID vehicleId,
    @NotNull UUID dealershipId,
    @NotNull UUID serviceTypeId,
    @NotNull UUID technicianId,
    @NotNull UUID serviceBayId,
    @NotNull @Future OffsetDateTime startTime
) {}
