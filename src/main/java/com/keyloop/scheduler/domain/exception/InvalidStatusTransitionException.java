package com.keyloop.scheduler.domain.exception;

import com.keyloop.scheduler.domain.valueobject.AppointmentStatus;
import java.util.UUID;

/** Thrown when a state transition is illegal (e.g., CANCELLED → CONFIRMED). Maps to HTTP 422. */
public class InvalidStatusTransitionException extends RuntimeException {
    public InvalidStatusTransitionException(UUID id, AppointmentStatus from, AppointmentStatus to) {
        super("Cannot transition appointment [%s] from %s to %s".formatted(id, from, to));
    }
}
