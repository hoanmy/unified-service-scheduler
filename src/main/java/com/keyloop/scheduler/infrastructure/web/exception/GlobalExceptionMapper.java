package com.keyloop.scheduler.infrastructure.web.exception;

import com.keyloop.scheduler.domain.exception.AppointmentNotFoundException;
import com.keyloop.scheduler.domain.exception.DoubleBookingException;
import com.keyloop.scheduler.domain.exception.InvalidStatusTransitionException;
import com.keyloop.scheduler.domain.exception.ResourceLockedException;
import com.keyloop.scheduler.infrastructure.web.dto.response.ConflictResponse;
import jakarta.validation.ConstraintViolationException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

/**
 * Global exception mapper — translates domain exceptions to structured HTTP responses.
 *
 * <p>Mapping table:
 * <ul>
 *   <li>{@link DoubleBookingException} → 409 Conflict (Section 6.2: RESOURCE_ALREADY_ALLOCATED)</li>
 *   <li>{@link ResourceLockedException} → 409 Conflict (Redis lock held by competing request)</li>
 *   <li>{@link AppointmentNotFoundException} → 404 Not Found</li>
 *   <li>{@link InvalidStatusTransitionException} → 422 Unprocessable Entity</li>
 *   <li>{@link ConstraintViolationException} → 400 Bad Request</li>
 * </ul>
 */
@Provider
public class GlobalExceptionMapper implements ExceptionMapper<Exception> {

    private static final Logger LOG = Logger.getLogger(GlobalExceptionMapper.class);

    @Override
    public Response toResponse(Exception exception) {
        return switch (exception) {
            case DoubleBookingException e -> {
                LOG.debugf("Double-booking prevented: %s", e.getMessage());
                yield Response.status(Response.Status.CONFLICT)
                    .entity(new ConflictResponse(
                        "RESOURCE_ALREADY_ALLOCATED",
                        "The requested technician or service bay is no longer available for the specified time slot.",
                        buildSuggestedSlotsUrl(e)
                    )).build();
            }
            case ResourceLockedException e -> {
                LOG.debugf("Resource lock contention: %s", e.getMessage());
                yield Response.status(Response.Status.CONFLICT)
                    .entity(new ConflictResponse(
                        "RESOURCE_LOCKED",
                        "This slot is being processed by a concurrent request. Please refresh availability.",
                        null
                    )).build();
            }
            case AppointmentNotFoundException e -> Response.status(Response.Status.NOT_FOUND)
                .entity(java.util.Map.of("error", e.getMessage())).build();

            case InvalidStatusTransitionException e -> Response.status(422)
                .entity(java.util.Map.of("error", e.getMessage())).build();

            case ConstraintViolationException e -> Response.status(Response.Status.BAD_REQUEST)
                .entity(java.util.Map.of("error", "Validation failed",
                    "violations", e.getConstraintViolations().stream()
                        .map(cv -> cv.getPropertyPath() + ": " + cv.getMessage())
                        .toList()
                )).build();

            case NotFoundException e -> Response.status(Response.Status.NOT_FOUND)
                .entity(java.util.Map.of("error", e.getMessage())).build();

            default -> {
                LOG.errorf(exception, "Unhandled exception: %s", exception.getMessage());
                yield Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(java.util.Map.of("error", "Internal server error")).build();
            }
        };
    }

    private String buildSuggestedSlotsUrl(DoubleBookingException e) {
        return "/api/v1/dealerships/unknown/availability?service_type_id=unknown&start_date=today&end_date=today";
    }
}
