package com.keyloop.scheduler.domain.valueobject;

/**
 * Appointment lifecycle state machine.
 *
 * <p>Business rule (Section 2.3): Binary state — appointments exist only in
 * {@code CONFIRMED} or {@code CANCELLED} states. Partial/orphan records are
 * rejected at the database level.
 *
 * <p>GiST exclusion constraints apply only where {@code status = 'CONFIRMED'},
 * allowing reuse of time slots after cancellation.
 */
public enum AppointmentStatus {

    /**
     * Appointment has been successfully booked. Resources (technician + bay)
     * are held. GiST exclusion constraints are active for this status.
     */
    CONFIRMED,

    /**
     * Appointment was cancelled. Resources are released. The time slot becomes
     * available again in Elasticsearch within the eventual consistency window
     * (target: &lt; 200ms after CDC propagation).
     */
    CANCELLED;

    /**
     * Determines if this status allows resource holding.
     *
     * @return true only for CONFIRMED — the only status subject to exclusion constraints
     */
    public boolean holdsResources() {
        return this == CONFIRMED;
    }

    /**
     * Validates a legal state transition.
     *
     * <p>Currently supported: CONFIRMED → CANCELLED (irreversible).
     * Cancelling an already cancelled appointment is idempotent.
     *
     * @param target desired next status
     * @return true if the transition is valid
     */
    public boolean canTransitionTo(AppointmentStatus target) {
        return switch (this) {
            case CONFIRMED -> target == CANCELLED;
            case CANCELLED -> target == CANCELLED; // idempotent
        };
    }
}
