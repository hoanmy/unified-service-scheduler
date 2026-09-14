package com.keyloop.scheduler.infrastructure.web.dto.request;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Request body for POST /api/v1/appointments (FR-02). */
public record BookAppointmentRequest(
    @NotNull UUID customerId,
    @NotNull UUID vehicleId,
    @NotNull UUID dealershipId,
    @NotNull UUID serviceTypeId,
    @NotNull UUID technicianId,
    @NotNull UUID serviceBayId,
    @NotNull @Future OffsetDateTime startTime
) {}
