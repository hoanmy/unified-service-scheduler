package com.keyloop.scheduler.infrastructure.web.dto.response;

import com.keyloop.scheduler.domain.valueobject.AppointmentStatus;
import java.time.OffsetDateTime;
import java.util.UUID;

/** FR-02 Booking success response payload (HTTP 201). */
public record AppointmentResponse(
    UUID appointmentId,
    AppointmentStatus status,
    UUID dealershipId,
    UUID customerId,
    UUID vehicleId,
    UUID serviceTypeId,
    UUID technicianId,
    UUID serviceBayId,
    OffsetDateTime startTime,
    OffsetDateTime endTime,
    OffsetDateTime createdAt
) {}
