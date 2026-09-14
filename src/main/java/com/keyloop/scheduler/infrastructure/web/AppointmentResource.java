package com.keyloop.scheduler.infrastructure.web;

import com.keyloop.scheduler.application.port.in.BookAppointmentUseCase;
import com.keyloop.scheduler.application.port.in.CancelAppointmentUseCase;
import com.keyloop.scheduler.application.port.in.command.BookAppointmentCommand;
import com.keyloop.scheduler.application.port.in.command.CancelAppointmentCommand;
import com.keyloop.scheduler.infrastructure.web.dto.request.BookAppointmentRequest;
import com.keyloop.scheduler.infrastructure.web.dto.request.CancelAppointmentRequest;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * FR-02, FR-03, FR-04: Reservation Service REST endpoints.
 *
 * <p>Booking (POST /api/v1/appointments):
 * <ul>
 *   <li>Requires {@code Idempotency-Key} header (UUIDv4) — FR-03.</li>
 *   <li>Executes 5-phase booking flow via {@link BookAppointmentUseCase}.</li>
 *   <li>Returns {@code 201 Created} on success, {@code 409 Conflict} on contention.</li>
 * </ul>
 *
 * <p>Cancellation (PATCH /api/v1/appointments/{id}/status):
 * <ul>
 *   <li>Transitions to CANCELLED, releases resources, publishes outbox event.</li>
 *   <li>Returns {@code 200 OK}.</li>
 * </ul>
 */
@Path("/api/v1/appointments")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Reservation Service", description = "FR-02/03/04: Booking, Idempotency, and Cancellation")
public class AppointmentResource {

    private static final Logger LOG = Logger.getLogger(AppointmentResource.class);
    private static final Pattern UUID_PATTERN =
        Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    @Inject BookAppointmentUseCase bookAppointmentUseCase;
    @Inject CancelAppointmentUseCase cancelAppointmentUseCase;

    /**
     * FR-02 + FR-03: Book an appointment with idempotency.
     *
     * @param idempotencyKey Client-generated UUIDv4 preventing duplicate submissions.
     *                       Required. Cached for 24 hours.
     */
    @POST
    @Operation(
        summary = "Book a service appointment",
        description = "Validates resources, acquires distributed lock, persists confirmed appointment. " +
            "Idempotency-Key header required. Same key returns cached 201 within 24h window."
    )
    @APIResponse(responseCode = "201", description = "Appointment confirmed")
    @APIResponse(responseCode = "400", description = "Invalid request body or missing Idempotency-Key")
    @APIResponse(responseCode = "409", description = "Technician or bay already allocated (double-booking prevented)")
    public Uni<Response> bookAppointment(
        @HeaderParam("Idempotency-Key")
        @NotBlank
        @Parameter(description = "Client-generated UUIDv4 for duplicate request prevention", required = true)
        String idempotencyKey,

        @Valid BookAppointmentRequest request
    ) {
        // Validate Idempotency-Key format
        if (!UUID_PATTERN.matcher(idempotencyKey.toLowerCase()).matches()) {
            return Uni.createFrom().item(
                Response.status(Response.Status.BAD_REQUEST)
                    .entity("Idempotency-Key must be a valid UUIDv4")
                    .build()
            );
        }

        BookAppointmentCommand command = new BookAppointmentCommand(
            idempotencyKey,
            request.customerId(), request.vehicleId(), request.dealershipId(),
            request.serviceTypeId(), request.technicianId(), request.serviceBayId(),
            request.startTime()
        );

        return bookAppointmentUseCase.execute(command)
            .map(appointment -> Response
                .created(URI.create("/api/v1/appointments/" + appointment.appointmentId()))
                .entity(appointment)
                .build());
    }

    /**
     * FR-04: Cancel an appointment and release held resources.
     */
    @PATCH
    @Path("/{appointmentId}/status")
    @Operation(
        summary = "Cancel an appointment",
        description = "Transitions appointment to CANCELLED status. Releases technician and bay resources. " +
            "Publishes AppointmentCancelled event to Kafka for async ES sync (< 200ms eventual consistency)."
    )
    @APIResponse(responseCode = "200", description = "Appointment cancelled successfully")
    @APIResponse(responseCode = "404", description = "Appointment not found")
    @APIResponse(responseCode = "422", description = "Invalid status transition")
    public Uni<Response> cancelAppointment(
        @PathParam("appointmentId")
        @Parameter(description = "Appointment UUID to cancel", required = true)
        UUID appointmentId,

        @Valid CancelAppointmentRequest request
    ) {
        CancelAppointmentCommand command = new CancelAppointmentCommand(appointmentId, request.reason());

        return cancelAppointmentUseCase.execute(command)
            .map(__ -> Response.ok().entity(
                java.util.Map.of("appointmentId", appointmentId, "status", "CANCELLED")
            ).build());
    }
}
