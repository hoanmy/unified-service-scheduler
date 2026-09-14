package com.keyloop.scheduler.application.port.out;

import com.keyloop.scheduler.domain.entity.Appointment;
import io.smallrye.mutiny.Uni;
import java.util.UUID;

/** Output port for appointment persistence. Implemented by the infrastructure persistence adapter. */
public interface AppointmentRepositoryPort {
    Uni<Appointment> save(Appointment appointment);
    Uni<Appointment> findById(UUID id);
    Uni<Appointment> updateStatus(UUID id, com.keyloop.scheduler.domain.valueobject.AppointmentStatus newStatus);
}
