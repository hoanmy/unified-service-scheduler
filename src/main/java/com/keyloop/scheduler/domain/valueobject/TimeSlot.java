package com.keyloop.scheduler.domain.valueobject;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * Immutable value object representing a half-open temporal interval [start, end).
 *
 * <p>Models a service appointment time block. The half-open interval semantics
 * match PostgreSQL's {@code TSTZRANGE} type, enabling direct overlap detection
 * via the {@code &&} operator in GiST exclusion constraints.
 *
 * <p>Business invariant: {@code start} must be strictly before {@code end}.
 */
public record TimeSlot(OffsetDateTime start, OffsetDateTime end) {

    public TimeSlot {
        Objects.requireNonNull(start, "TimeSlot start must not be null");
        Objects.requireNonNull(end, "TimeSlot end must not be null");
        if (!start.isBefore(end)) {
            throw new IllegalArgumentException(
                "TimeSlot start [%s] must be strictly before end [%s]".formatted(start, end));
        }
    }

    /**
     * Factory method: creates a TimeSlot given a start time and duration in minutes.
     *
     * <p>Business rule (Section 2.2): {@code EndTime = StartTime + DurationMinutes}
     *
     * @param start           appointment start timestamp
     * @param durationMinutes service type duration (must be > 0)
     * @return immutable TimeSlot covering the service window
     */
    public static TimeSlot of(OffsetDateTime start, int durationMinutes) {
        Objects.requireNonNull(start, "start must not be null");
        if (durationMinutes <= 0) {
            throw new IllegalArgumentException("durationMinutes must be positive, got: " + durationMinutes);
        }
        return new TimeSlot(start, start.plusMinutes(durationMinutes));
    }

    /**
     * Detects temporal overlap with another TimeSlot.
     *
     * <p>Two half-open intervals [a, b) and [c, d) overlap iff a < d && c < b.
     * This mirrors PostgreSQL's {@code &&} operator on TSTZRANGE.
     *
     * @param other the other slot to check against
     * @return true if the intervals overlap
     */
    public boolean overlaps(TimeSlot other) {
        Objects.requireNonNull(other, "other TimeSlot must not be null");
        return this.start.isBefore(other.end) && other.start.isBefore(this.end);
    }

    /**
     * Duration of this time slot in minutes.
     */
    public long durationMinutes() {
        return java.time.Duration.between(start, end).toMinutes();
    }

    @Override
    public String toString() {
        return "[%s, %s)".formatted(start, end);
    }
}
