package com.keyloop.scheduler.domain.exception;

import java.util.UUID;

/** Thrown when a requested appointment does not exist. Maps to HTTP 404. */
public class AppointmentNotFoundException extends RuntimeException {
    public AppointmentNotFoundException(UUID id) {
        super("Appointment not found: " + id);
    }
}
