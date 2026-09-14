package com.keyloop.scheduler.domain.exception;

import java.util.UUID;

/**
 * Thrown when an attempt to book an appointment is rejected because
 * the requested technician or service bay already has an overlapping CONFIRMED booking.
 *
 * <p>Maps to HTTP 409 Conflict at the web layer.
 * The client should refresh availability and retry with a new slot.
 */
public class DoubleBookingException extends RuntimeException {

    private final UUID resourceId;
    private final String resourceType;

    public DoubleBookingException(UUID resourceId, String resourceType) {
        super("Resource %s [%s] is already allocated for the requested time slot."
            .formatted(resourceType, resourceId));
        this.resourceId = resourceId;
        this.resourceType = resourceType;
    }

    public UUID getResourceId()   { return resourceId; }
    public String getResourceType() { return resourceType; }
}
