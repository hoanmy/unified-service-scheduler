package com.keyloop.scheduler.infrastructure.web;

import com.keyloop.scheduler.application.port.in.CheckAvailabilityUseCase;
import com.keyloop.scheduler.application.port.in.query.AvailabilityQuery;
import com.keyloop.scheduler.infrastructure.web.dto.response.AvailabilityResponse;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.resteasy.reactive.RestQuery;

import java.time.LocalDate;
import java.util.UUID;

/**
 * FR-01: Availability Search REST endpoint.
 *
 * <p>GET /api/v1/dealerships/{dealershipId}/availability
 *
 * <p>Read path (CQRS): queries Elasticsearch exclusively — no RDBMS access.
 * Response headers include {@code Cache-Control: public, max-age=15} to
 * enable CDN/Gateway edge caching, absorbing up to 90% of repeated queries.
 *
 * <p>SLO: p95 &lt; 200ms, p99 &lt; 500ms at 100,000 RPS (Section 3, FR-01).
 */
@Path("/api/v1/dealerships/{dealershipId}/availability")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Schedule Service", description = "FR-01: Real-time slot availability queries")
public class AvailabilityResource {

    @Inject CheckAvailabilityUseCase checkAvailabilityUseCase;

    @GET
    @Operation(
        summary = "Check available appointment slots",
        description = "Returns available time slots with pre-assigned technician and bay candidates. " +
            "Backed by Elasticsearch for sub-200ms read latency. " +
            "Response is cached at CDN edge for 15 seconds."
    )
    @APIResponse(responseCode = "200", description = "Available slots returned successfully")
    @APIResponse(responseCode = "400", description = "Invalid query parameters")
    @APIResponse(responseCode = "503", description = "Search service unavailable (circuit breaker open)")
    public Uni<Response> checkAvailability(
        @PathParam("dealershipId")
        @Parameter(description = "Dealership UUID", required = true)
        UUID dealershipId,

        @RestQuery("service_type_id")
        @NotNull
        @Parameter(description = "Service type UUID", required = true)
        UUID serviceTypeId,

        @RestQuery("start_date")
        @NotNull
        @Parameter(description = "Start date (ISO-8601: yyyy-MM-dd)", required = true, example = "2026-09-10")
        LocalDate startDate,

        @RestQuery("end_date")
        @NotNull
        @Parameter(description = "End date (ISO-8601: yyyy-MM-dd)", required = true, example = "2026-09-10")
        LocalDate endDate
    ) {
        AvailabilityQuery query = new AvailabilityQuery(dealershipId, serviceTypeId, startDate, endDate);

        return checkAvailabilityUseCase.execute(query)
            .map(availability -> Response.ok(availability)
                // FR-01: Edge cache 15 seconds (absorbs 90% of read traffic via CDN)
                .header("Cache-Control", "public, max-age=15")
                .header("ETag", String.valueOf(availability.hashCode()))
                .build());
    }
}
