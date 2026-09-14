package com.keyloop.scheduler.domain.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Technician resource entity.
 *
 * <p>Skill-level hierarchy: a technician qualifies for a service if and only if
 * {@code technicianSkillLevel >= serviceRequiredSkillLevel} (Section 2.2).
 *
 * <p>Framework-free pure Java domain entity.
 */
public record Technician(
    UUID id,
    UUID dealershipId,
    String name,
    int skillLevel,
    String status,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {
    public Technician {
        if (skillLevel < 1 || skillLevel > 10) {
            throw new IllegalArgumentException("skillLevel must be between 1 and 10, got: " + skillLevel);
        }
    }

    /**
     * Determines if this technician is qualified to perform a service.
     *
     * <p>Section 2.2: TechnicianSkillLevel >= ServiceRequiredSkillLevel
     *
     * @param requiredSkillLevel minimum skill level required by the service type
     * @return true if the technician meets or exceeds the requirement
     */
    public boolean isQualifiedFor(int requiredSkillLevel) {
        return this.skillLevel >= requiredSkillLevel;
    }

    public boolean isAvailable() {
        return "AVAILABLE".equals(status);
    }
}
