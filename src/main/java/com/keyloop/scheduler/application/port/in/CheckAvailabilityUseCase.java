package com.keyloop.scheduler.application.port.in;

import com.keyloop.scheduler.application.port.in.query.AvailabilityQuery;
import com.keyloop.scheduler.infrastructure.web.dto.response.AvailabilityResponse;
import io.smallrye.mutiny.Uni;

/**
 * Input port — FR-01: Availability Search.
 *
 * <p>Contract: returns available time slots for a dealership/service type within
 * a date range. Delegates to Elasticsearch for sub-50ms read performance.
 *
 * <p>Response includes HTTP caching hint: {@code Cache-Control: public, max-age=15}.
 */
public interface CheckAvailabilityUseCase {
    Uni<AvailabilityResponse> execute(AvailabilityQuery query);
}
