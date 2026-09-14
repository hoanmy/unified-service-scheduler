package com.keyloop.scheduler.application.usecase;

import com.keyloop.scheduler.application.port.in.command.BookAppointmentCommand;
import com.keyloop.scheduler.application.port.out.AppointmentRepositoryPort;
import com.keyloop.scheduler.application.port.out.DistributedLockPort;
import com.keyloop.scheduler.application.port.out.IdempotencyPort;
import com.keyloop.scheduler.application.port.out.OutboxEventRepositoryPort;
import com.keyloop.scheduler.application.port.out.ServiceTypeRepositoryPort;
import com.keyloop.scheduler.domain.entity.Appointment;
import com.keyloop.scheduler.domain.entity.OutboxEvent;
import com.keyloop.scheduler.domain.exception.DoubleBookingException;
import com.keyloop.scheduler.domain.exception.ResourceLockedException;
import com.keyloop.scheduler.domain.valueobject.AppointmentStatus;
import com.keyloop.scheduler.domain.valueobject.TimeSlot;
import com.keyloop.scheduler.infrastructure.web.dto.response.AppointmentResponse;
import com.keyloop.scheduler.infrastructure.web.dto.response.ServiceTypeDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.helpers.test.UniAssertSubscriber;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link BookAppointmentUseCaseImpl}.
 *
 * <p>Tests the 5-phase booking flow in isolation using mocked ports.
 * No infrastructure dependencies (Redis, PostgreSQL, Kafka).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BookAppointmentUseCase — 5-Phase Booking Flow")
class BookAppointmentUseCaseTest {

    @Mock AppointmentRepositoryPort appointmentRepository;
    @Mock OutboxEventRepositoryPort outboxRepository;
    @Mock DistributedLockPort lockPort;
    @Mock IdempotencyPort idempotencyPort;
    @Mock ServiceTypeRepositoryPort serviceTypeRepository;

    @InjectMocks
    BookAppointmentUseCaseImpl useCase;

    private UUID dealershipId;
    private UUID customerId;
    private UUID vehicleId;
    private UUID serviceTypeId;
    private UUID technicianId;
    private UUID serviceBayId;
    private String idempotencyKey;
    private OffsetDateTime startTime;
    private ServiceTypeDto serviceType;

    @BeforeEach
    void setUp() {
        useCase.objectMapper = new ObjectMapper()
            .findAndRegisterModules(); // for OffsetDateTime serialization
        useCase.lockTtlSeconds         = 5;
        useCase.idempotencyTtlSeconds  = 86400;

        dealershipId  = UUID.randomUUID();
        customerId    = UUID.randomUUID();
        vehicleId     = UUID.randomUUID();
        serviceTypeId = UUID.randomUUID();
        technicianId  = UUID.randomUUID();
        serviceBayId  = UUID.randomUUID();
        idempotencyKey = UUID.randomUUID().toString();
        startTime      = OffsetDateTime.now().plusDays(1);

        serviceType = new ServiceTypeDto(serviceTypeId, dealershipId, "Oil Change", 45, 1);
    }

    // ── Phase 1: Idempotency ──────────────────────────────────────────────────

    @Test
    @DisplayName("Phase 1: Returns cached response for duplicate idempotency key (< 5ms)")
    void shouldReturnCachedResponseForDuplicateKey() {
        String cachedJson = """
            {"appointmentId":"%s","status":"CONFIRMED","dealershipId":"%s",
            "customerId":"%s","vehicleId":"%s","serviceTypeId":"%s",
            "technicianId":"%s","serviceBayId":"%s",
            "startTime":"%s","endTime":"%s","createdAt":"%s"}
            """.formatted(UUID.randomUUID(), dealershipId, customerId, vehicleId,
                serviceTypeId, technicianId, serviceBayId,
                startTime, startTime.plusMinutes(45), OffsetDateTime.now());

        when(idempotencyPort.findCachedResponse(idempotencyKey))
            .thenReturn(Uni.createFrom().item(Optional.of(cachedJson)));

        BookAppointmentCommand command = buildCommand();

        UniAssertSubscriber<AppointmentResponse> subscriber = useCase.execute(command)
            .subscribe().withSubscriber(UniAssertSubscriber.create());

        subscriber.awaitItem().assertCompleted();

        // CRITICAL: No downstream calls should be made (idempotency cache hit)
        verifyNoInteractions(lockPort, appointmentRepository, outboxRepository, serviceTypeRepository);
    }

    // ── Phase 2: Lock Contention ──────────────────────────────────────────────

    @Test
    @DisplayName("Phase 2: Returns 409 ResourceLocked when tech lock is held by competing request")
    void shouldThrowResourceLockedWhenTechLockHeld() {
        when(idempotencyPort.findCachedResponse(anyString()))
            .thenReturn(Uni.createFrom().item(Optional.empty()));
        when(serviceTypeRepository.findById(serviceTypeId))
            .thenReturn(Uni.createFrom().item(serviceType));
        // Tech lock is already held
        when(lockPort.tryAcquire(contains("lock:tech"), anyInt()))
            .thenReturn(Uni.createFrom().item(false));

        BookAppointmentCommand command = buildCommand();

        UniAssertSubscriber<AppointmentResponse> subscriber = useCase.execute(command)
            .subscribe().withSubscriber(UniAssertSubscriber.create());

        subscriber.awaitFailure().assertFailed()
            .assertFailedWith(ResourceLockedException.class);

        // CRITICAL: PostgreSQL must NOT be touched
        verifyNoInteractions(appointmentRepository, outboxRepository);
    }

    @Test
    @DisplayName("Phase 2: Releases tech lock and returns 409 when bay lock is held")
    void shouldReleaseTechLockWhenBayLockHeld() {
        when(idempotencyPort.findCachedResponse(anyString()))
            .thenReturn(Uni.createFrom().item(Optional.empty()));
        when(serviceTypeRepository.findById(serviceTypeId))
            .thenReturn(Uni.createFrom().item(serviceType));
        when(lockPort.tryAcquire(contains("lock:tech"), anyInt()))
            .thenReturn(Uni.createFrom().item(true));
        when(lockPort.tryAcquire(contains("lock:bay"), anyInt()))
            .thenReturn(Uni.createFrom().item(false));
        when(lockPort.release(contains("lock:tech")))
            .thenReturn(Uni.createFrom().voidItem());

        BookAppointmentCommand command = buildCommand();

        UniAssertSubscriber<AppointmentResponse> subscriber = useCase.execute(command)
            .subscribe().withSubscriber(UniAssertSubscriber.create());

        subscriber.awaitFailure().assertFailedWith(ResourceLockedException.class);

        // CRITICAL: Tech lock must be released when bay lock fails
        verify(lockPort).release(contains("lock:tech"));
        verifyNoInteractions(appointmentRepository, outboxRepository);
    }

    // ── Phase 3: ACID Transaction ─────────────────────────────────────────────

    @Test
    @DisplayName("Phase 3: Throws DoubleBookingException on PG GiST exclusion constraint violation")
    void shouldThrowDoubleBookingOnConstraintViolation() {
        when(idempotencyPort.findCachedResponse(anyString()))
            .thenReturn(Uni.createFrom().item(Optional.empty()));
        when(serviceTypeRepository.findById(serviceTypeId))
            .thenReturn(Uni.createFrom().item(serviceType));
        when(lockPort.tryAcquire(anyString(), anyInt()))
            .thenReturn(Uni.createFrom().item(true));

        // PostgreSQL GiST constraint violation
        when(appointmentRepository.save(any(Appointment.class)))
            .thenReturn(Uni.createFrom().failure(
                new DoubleBookingException(technicianId, "TECHNICIAN/BAY")));

        BookAppointmentCommand command = buildCommand();

        UniAssertSubscriber<AppointmentResponse> subscriber = useCase.execute(command)
            .subscribe().withSubscriber(UniAssertSubscriber.create());

        subscriber.awaitFailure().assertFailedWith(DoubleBookingException.class);
    }

    // ── Full Happy Path ───────────────────────────────────────────────────────

    @Test
    @DisplayName("Happy Path: Full 5-phase booking flow succeeds and returns 201 response")
    void shouldSuccessfullyBookAppointment() {
        TimeSlot timeSlot = TimeSlot.of(startTime, 45);
        Appointment mockAppointment = Appointment.create(
            dealershipId, customerId, vehicleId, serviceTypeId, technicianId, serviceBayId, timeSlot);

        when(idempotencyPort.findCachedResponse(anyString()))
            .thenReturn(Uni.createFrom().item(Optional.empty()));
        when(serviceTypeRepository.findById(serviceTypeId))
            .thenReturn(Uni.createFrom().item(serviceType));
        when(lockPort.tryAcquire(anyString(), anyInt()))
            .thenReturn(Uni.createFrom().item(true));
        when(appointmentRepository.save(any(Appointment.class)))
            .thenReturn(Uni.createFrom().item(mockAppointment));
        when(outboxRepository.save(any(OutboxEvent.class)))
            .thenReturn(Uni.createFrom().item(mock(OutboxEvent.class)));
        when(lockPort.release(anyString()))
            .thenReturn(Uni.createFrom().voidItem());
        when(idempotencyPort.cacheResponse(anyString(), anyString(), anyInt()))
            .thenReturn(Uni.createFrom().voidItem());

        BookAppointmentCommand command = buildCommand();

        UniAssertSubscriber<AppointmentResponse> subscriber = useCase.execute(command)
            .subscribe().withSubscriber(UniAssertSubscriber.create());

        AppointmentResponse response = subscriber.awaitItem().assertCompleted().getItem();

        assertThat(response).isNotNull();
        assertThat(response.status()).isEqualTo(AppointmentStatus.CONFIRMED);
        assertThat(response.dealershipId()).isEqualTo(dealershipId);

        // Verify all 5 phases executed
        verify(idempotencyPort).findCachedResponse(idempotencyKey);           // Phase 1
        verify(lockPort, times(2)).tryAcquire(anyString(), anyInt());         // Phase 2
        verify(appointmentRepository).save(any(Appointment.class));           // Phase 3
        verify(outboxRepository).save(any(OutboxEvent.class));                // Phase 3 (outbox)
        verify(lockPort, times(2)).release(anyString());                      // Phase 4
        verify(idempotencyPort).cacheResponse(eq(idempotencyKey), anyString(), eq(86400)); // Phase 4
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private BookAppointmentCommand buildCommand() {
        return new BookAppointmentCommand(
            idempotencyKey, customerId, vehicleId, dealershipId,
            serviceTypeId, technicianId, serviceBayId, startTime
        );
    }
}
