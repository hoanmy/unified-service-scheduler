package com.keyloop.scheduler.infrastructure.web.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Request body for PATCH /api/v1/appointments/{id}/status (FR-04). */
public record CancelAppointmentRequest(
    @NotBlank @Pattern(regexp = "CANCELLED") String status,
    @NotBlank String reason
) {}
