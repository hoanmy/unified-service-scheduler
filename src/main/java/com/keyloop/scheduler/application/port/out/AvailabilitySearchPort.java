package com.keyloop.scheduler.application.port.out;

import com.keyloop.scheduler.application.port.in.query.AvailabilityQuery;
import com.keyloop.scheduler.infrastructure.web.dto.response.AvailableSlotDto;
import io.smallrye.mutiny.Uni;
import java.util.List;

/** Output port for Elasticsearch availability queries. */
public interface AvailabilitySearchPort {
    Uni<List<AvailableSlotDto>> findAvailableSlots(AvailabilityQuery query, int durationMinutes, int requiredSkillLevel);
}
