package com.keyloop.scheduler.domain.entity;

import java.util.UUID;

/**
 * Domain event produced into the Transactional Outbox table.
 *
 * <p>Written atomically within the same PostgreSQL transaction as the
 * {@link Appointment} insert/update. Debezium CDC tails the WAL and
 * publishes to Kafka without any dual-write hazard.
 *
 * <p>Partition key: {@code dealershipId} — guarantees per-dealership ordering
 * in Kafka (Section 9.1, Step 3).
 */
public record OutboxEvent(
    UUID id,
    String aggregateType,
    String aggregateId,
    String eventType,
    UUID dealershipId,
    String payload   // JSON serialized event payload
) {
    /**
     * Creates an outbox event for an appointment state change.
     *
     * @param appointment   the appointment that changed state
     * @param eventType     the event type string (e.g., "AppointmentBooked")
     * @param jsonPayload   pre-serialized JSON payload
     */
    public static OutboxEvent forAppointment(Appointment appointment, String eventType, String jsonPayload) {
        return new OutboxEvent(
            UUID.randomUUID(),
            "APPOINTMENT",
            appointment.getId().toString(),
            eventType,
            appointment.getDealershipId(),
            jsonPayload
        );
    }
}
