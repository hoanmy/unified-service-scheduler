package com.keyloop.scheduler.domain.entity;

import com.keyloop.scheduler.domain.valueobject.AppointmentStatus;
import com.keyloop.scheduler.domain.valueobject.EventType;
import com.keyloop.scheduler.domain.valueobject.TimeSlot;
import com.keyloop.scheduler.domain.exception.InvalidStatusTransitionException;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Core domain entity — Appointment (Aggregate Root).
 *
 * <p>Represents a confirmed service booking binding a customer's vehicle to
 * a technician and service bay within a specific time window at a dealership.
 *
 * <p>Business rules enforced here (Clean Architecture: enterprise rules live in the domain):
 * <ul>
 *   <li>Binary integrity: all 6 references must be non-null (Section 2.3).</li>
 *   <li>State transition: only CONFIRMED → CANCELLED is legal.</li>
 *   <li>Skill matching: validated by the use case layer before construction.</li>
 * </ul>
 *
 * <p>This entity is framework-free: no JPA, no Quarkus annotations.
 * The infrastructure layer maps it to/from {@code AppointmentJpaEntity}.
 */
public class Appointment {

    private final UUID id;
    private final UUID dealershipId;
    private final UUID customerId;
    private final UUID vehicleId;
    private final UUID serviceTypeId;
    private final UUID technicianId;
    private final UUID serviceBayId;
    private final TimeSlot timeSlot;
    private AppointmentStatus status;
    private final OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    // Private constructor — use static factory methods
    private Appointment(UUID id, UUID dealershipId, UUID customerId, UUID vehicleId,
                        UUID serviceTypeId, UUID technicianId, UUID serviceBayId,
                        TimeSlot timeSlot, AppointmentStatus status,
                        OffsetDateTime createdAt, OffsetDateTime updatedAt) {
        this.id = id;
        this.dealershipId = dealershipId;
        this.customerId = customerId;
        this.vehicleId = vehicleId;
        this.serviceTypeId = serviceTypeId;
        this.technicianId = technicianId;
        this.serviceBayId = serviceBayId;
        this.timeSlot = timeSlot;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /**
     * Creates a new CONFIRMED appointment (used by BookAppointmentUseCase).
     *
     * <p>Assigns a new UUID. All resources must be resolved before calling.
     */
    public static Appointment create(UUID dealershipId, UUID customerId, UUID vehicleId,
                                     UUID serviceTypeId, UUID technicianId, UUID serviceBayId,
                                     TimeSlot timeSlot) {
        validateNotNull(dealershipId, "dealershipId");
        validateNotNull(customerId, "customerId");
        validateNotNull(vehicleId, "vehicleId");
        validateNotNull(serviceTypeId, "serviceTypeId");
        validateNotNull(technicianId, "technicianId");
        validateNotNull(serviceBayId, "serviceBayId");
        validateNotNull(timeSlot, "timeSlot");

        OffsetDateTime now = OffsetDateTime.now();
        return new Appointment(
            UUID.randomUUID(), dealershipId, customerId, vehicleId,
            serviceTypeId, technicianId, serviceBayId,
            timeSlot, AppointmentStatus.CONFIRMED, now, now
        );
    }

    /**
     * Reconstitutes an existing appointment from persistence (used by repository adapters).
     */
    public static Appointment reconstitute(UUID id, UUID dealershipId, UUID customerId,
                                           UUID vehicleId, UUID serviceTypeId, UUID technicianId,
                                           UUID serviceBayId, TimeSlot timeSlot,
                                           AppointmentStatus status, OffsetDateTime createdAt,
                                           OffsetDateTime updatedAt) {
        return new Appointment(id, dealershipId, customerId, vehicleId,
            serviceTypeId, technicianId, serviceBayId, timeSlot, status, createdAt, updatedAt);
    }

    /**
     * Cancels this appointment, releasing all held resources.
     *
     * <p>Triggers downstream slot restoration via the Outbox/CDC/Kafka pipeline.
     *
     * @throws InvalidStatusTransitionException if already cancelled
     */
    public void cancel() {
        if (!status.canTransitionTo(AppointmentStatus.CANCELLED)) {
            throw new InvalidStatusTransitionException(id, status, AppointmentStatus.CANCELLED);
        }
        this.status = AppointmentStatus.CANCELLED;
        this.updatedAt = OffsetDateTime.now();
    }

    /**
     * Determines the appropriate outbox event type for the current state.
     */
    public EventType toEventType() {
        return switch (status) {
            case CONFIRMED -> EventType.APPOINTMENT_BOOKED;
            case CANCELLED -> EventType.APPOINTMENT_CANCELLED;
        };
    }

    // ── Getters ───────────────────────────────────────────────────────────────
    public UUID getId()            { return id; }
    public UUID getDealershipId()  { return dealershipId; }
    public UUID getCustomerId()    { return customerId; }
    public UUID getVehicleId()     { return vehicleId; }
    public UUID getServiceTypeId() { return serviceTypeId; }
    public UUID getTechnicianId()  { return technicianId; }
    public UUID getServiceBayId()  { return serviceBayId; }
    public TimeSlot getTimeSlot()  { return timeSlot; }
    public AppointmentStatus getStatus() { return status; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    public boolean isConfirmed()  { return status == AppointmentStatus.CONFIRMED; }
    public boolean isCancelled()  { return status == AppointmentStatus.CANCELLED; }

    private static void validateNotNull(Object value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException("Appointment." + fieldName + " must not be null");
        }
    }

    @Override
    public String toString() {
        return "Appointment{id=%s, dealershipId=%s, status=%s, timeSlot=%s}"
            .formatted(id, dealershipId, status, timeSlot);
    }
}
