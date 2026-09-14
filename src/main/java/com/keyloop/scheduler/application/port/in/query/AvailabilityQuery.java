package com.keyloop.scheduler.application.port.in.query;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Query object for the FR-01 Availability Check use case.
 */
public record AvailabilityQuery(
    @NotNull UUID dealershipId,
    @NotNull UUID serviceTypeId,
    @NotNull LocalDate startDate,
    @NotNull LocalDate endDate
) {
    public AvailabilityQuery {
        if (endDate.isBefore(startDate)) {
            throw new IllegalArgumentException("endDate must not be before startDate");
        }
    }
}
