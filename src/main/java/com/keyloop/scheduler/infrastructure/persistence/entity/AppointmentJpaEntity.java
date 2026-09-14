package com.keyloop.scheduler.infrastructure.persistence.entity;

import io.quarkus.hibernate.reactive.panache.PanacheEntityBase;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * JPA entity mapping for the {@code appointment} table.
 *
 * <p>The {@code time_slot} column is a PostgreSQL {@code TSTZRANGE} type.
 * It is stored as a String in this entity and converted by the repository adapter,
 * since Hibernate Reactive does not natively support PostgreSQL range types out-of-the-box.
 * The GiST exclusion constraints are defined in the Flyway migration V1.
 */
@Entity
@Table(name = "appointment")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AppointmentJpaEntity extends PanacheEntityBase {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "dealership_id", nullable = false)
    private UUID dealershipId;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "vehicle_id", nullable = false)
    private UUID vehicleId;

    @Column(name = "service_type_id", nullable = false)
    private UUID serviceTypeId;

    @Column(name = "technician_id", nullable = false)
    private UUID technicianId;

    @Column(name = "service_bay_id", nullable = false)
    private UUID serviceBayId;

    /**
     * PostgreSQL TSTZRANGE stored as a formatted string: "[start,end)".
     * Example: "[2026-09-10 09:00:00+00,2026-09-10 09:45:00+00)"
     * Converted to/from {@link com.keyloop.scheduler.domain.valueobject.TimeSlot} by the adapter.
     */
    @Column(name = "time_slot", nullable = false, columnDefinition = "tstzrange")
    @JdbcTypeCode(SqlTypes.OTHER)
    private String timeSlot;

    @Column(name = "status", nullable = false, length = 50)
    private String status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
