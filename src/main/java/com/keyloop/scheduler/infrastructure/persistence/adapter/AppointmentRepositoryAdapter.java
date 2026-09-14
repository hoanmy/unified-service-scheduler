package com.keyloop.scheduler.infrastructure.persistence.adapter;

import com.keyloop.scheduler.application.port.out.AppointmentRepositoryPort;
import com.keyloop.scheduler.domain.entity.Appointment;
import com.keyloop.scheduler.domain.exception.AppointmentNotFoundException;
import com.keyloop.scheduler.domain.exception.DoubleBookingException;
import com.keyloop.scheduler.domain.valueobject.AppointmentStatus;
import com.keyloop.scheduler.domain.valueobject.TimeSlot;
import com.keyloop.scheduler.infrastructure.persistence.entity.AppointmentJpaEntity;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Adapter implementing {@link AppointmentRepositoryPort} using Hibernate Reactive Panache.
 *
 * <p>All write operations run within a reactive transaction (via {@code @WithTransaction}).
 * PostgreSQL GiST exclusion constraint violations are caught and rethrown as
 * {@link DoubleBookingException} for clean domain exception propagation.
 */
@ApplicationScoped
public class AppointmentRepositoryAdapter implements AppointmentRepositoryPort {

    private static final Logger LOG = Logger.getLogger(AppointmentRepositoryAdapter.class);

    // PostgreSQL TSTZRANGE format: "[2026-09-10T09:00:00Z,2026-09-10T09:45:00Z)"
    private static final DateTimeFormatter PG_TIMESTAMP_FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    @Override
    @WithTransaction
    public Uni<Appointment> save(Appointment appointment) {
        AppointmentJpaEntity entity = toEntity(appointment);
        return entity.persist()
            .map(__ -> appointment)
            .onFailure(ex -> isExclusionConstraintViolation(ex))
            .transform(ex -> {
                LOG.warnf("GiST exclusion constraint violated for appointment %s: %s",
                    appointment.getId(), ex.getMessage());
                return new DoubleBookingException(appointment.getTechnicianId(), "TECHNICIAN/BAY");
            });
    }

    @Override
    public Uni<Appointment> findById(UUID id) {
        return AppointmentJpaEntity.<AppointmentJpaEntity>findById(id)
            .onItem().ifNull().failWith(() -> new AppointmentNotFoundException(id))
            .map(this::toDomain);
    }

    @Override
    @WithTransaction
    public Uni<Appointment> updateStatus(UUID id, AppointmentStatus newStatus) {
        return AppointmentJpaEntity.<AppointmentJpaEntity>findById(id)
            .onItem().ifNull().failWith(() -> new AppointmentNotFoundException(id))
            .flatMap(entity -> {
                entity.setStatus(newStatus.name());
                entity.setUpdatedAt(OffsetDateTime.now());
                return entity.persist().map(__ -> toDomain(entity));
            });
    }

    // ── Mapping ───────────────────────────────────────────────────────────────

    private AppointmentJpaEntity toEntity(Appointment domain) {
        return AppointmentJpaEntity.builder()
            .id(domain.getId())
            .dealershipId(domain.getDealershipId())
            .customerId(domain.getCustomerId())
            .vehicleId(domain.getVehicleId())
            .serviceTypeId(domain.getServiceTypeId())
            .technicianId(domain.getTechnicianId())
            .serviceBayId(domain.getServiceBayId())
            .timeSlot(formatTstzRange(domain.getTimeSlot()))
            .status(domain.getStatus().name())
            .createdAt(domain.getCreatedAt())
            .updatedAt(domain.getUpdatedAt())
            .build();
    }

    private Appointment toDomain(AppointmentJpaEntity entity) {
        return Appointment.reconstitute(
            entity.getId(), entity.getDealershipId(), entity.getCustomerId(),
            entity.getVehicleId(), entity.getServiceTypeId(), entity.getTechnicianId(),
            entity.getServiceBayId(), parseTstzRange(entity.getTimeSlot()),
            AppointmentStatus.valueOf(entity.getStatus()),
            entity.getCreatedAt(), entity.getUpdatedAt()
        );
    }

    /**
     * Formats a TimeSlot as a PostgreSQL TSTZRANGE literal.
     * Format: "[2026-09-10T09:00:00+00:00,2026-09-10T09:45:00+00:00)"
     */
    private String formatTstzRange(TimeSlot slot) {
        return "[%s,%s)".formatted(
            slot.start().format(PG_TIMESTAMP_FMT),
            slot.end().format(PG_TIMESTAMP_FMT)
        );
    }

    /**
     * Parses a PostgreSQL TSTZRANGE string back to a TimeSlot.
     */
    private TimeSlot parseTstzRange(String tstzRange) {
        // Remove brackets: "[2026-09-10T09:00:00+00:00,2026-09-10T09:45:00+00:00)"
        String inner = tstzRange.replaceAll("[\\[\\)\\(\\]]", "").trim();
        String[] parts = inner.split(",");
        OffsetDateTime start = OffsetDateTime.parse(parts[0].trim(), PG_TIMESTAMP_FMT);
        OffsetDateTime end   = OffsetDateTime.parse(parts[1].trim(), PG_TIMESTAMP_FMT);
        return new TimeSlot(start, end);
    }

    private boolean isExclusionConstraintViolation(Throwable ex) {
        String msg = ex.getMessage();
        return msg != null && (
            msg.contains("exclude_technician_overlapping_slots") ||
            msg.contains("exclude_service_bay_overlapping_slots") ||
            msg.contains("ExclusionViolation")
        );
    }
}
