package com.keyloop.scheduler.infrastructure.web.dto.response;

import java.time.OffsetDateTime;
import java.util.UUID;

/** DTO for available time slot returned by the availability search. */
public record AvailableSlotDto(
    OffsetDateTime startTime,
    OffsetDateTime endTime,
    UUID assignedTechnicianId,
    UUID assignedServiceBayId
) {}
