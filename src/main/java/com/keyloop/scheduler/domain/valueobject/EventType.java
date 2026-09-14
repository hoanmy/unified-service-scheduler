package com.keyloop.scheduler.domain.valueobject;

/**
 * Domain event type identifiers for the Transactional Outbox pattern.
 *
 * <p>These values are written into {@code outbox_event.event_type} and
 * consumed by downstream workers via Kafka:
 * <ul>
 *   <li><b>Consumer Group A (Search Sync Worker):</b> updates Elasticsearch availability index.</li>
 *   <li><b>Consumer Group B (Notification Worker):</b> triggers Email/SMS/FCM/APNs dispatch.</li>
 * </ul>
 */
public enum EventType {

    /** Published when an appointment is successfully confirmed. Triggers slot removal from ES. */
    APPOINTMENT_BOOKED("AppointmentBooked"),

    /** Published when an appointment is cancelled. Triggers slot restoration in ES. */
    APPOINTMENT_CANCELLED("AppointmentCancelled");

    private final String value;

    EventType(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    @Override
    public String toString() {
        return value;
    }
}
