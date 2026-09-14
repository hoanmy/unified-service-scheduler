package com.keyloop.scheduler.infrastructure.web.dto.response;

/** HTTP 409 conflict response body (Section 6.2). */
public record ConflictResponse(
    String errorCode,
    String message,
    String suggestedSlotsUrl
) {}
