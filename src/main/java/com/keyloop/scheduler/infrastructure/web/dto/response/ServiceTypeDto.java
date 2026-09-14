package com.keyloop.scheduler.infrastructure.web.dto.response;

import java.util.UUID;

/** Lightweight DTO for service type metadata. */
public record ServiceTypeDto(UUID id, UUID dealershipId, String name, int durationMinutes, int requiredSkillLevel) {}
