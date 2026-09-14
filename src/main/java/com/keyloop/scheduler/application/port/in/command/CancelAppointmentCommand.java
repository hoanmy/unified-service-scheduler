package com.keyloop.scheduler.application.port.in.command;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Command object for the FR-04 Cancellation use case.
 */
public record CancelAppointmentCommand(
    @NotNull UUID appointmentId,
    @NotBlank String reason
) {}
