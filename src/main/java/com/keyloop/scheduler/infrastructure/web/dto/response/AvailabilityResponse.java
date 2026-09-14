package com.keyloop.scheduler.infrastructure.web.dto.response;

import java.util.List;
import java.util.UUID;

/** FR-01 Availability search response payload. */
public record AvailabilityResponse(
    UUID dealershipId,
    UUID serviceTypeId,
    int durationMinutes,
    List<AvailableSlotDto> availableSlots
) {}
