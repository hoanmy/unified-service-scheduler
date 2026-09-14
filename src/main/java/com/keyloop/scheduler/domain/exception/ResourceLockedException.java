package com.keyloop.scheduler.domain.exception;

/**
 * Thrown when the Redis distributed lock for a technician or bay slot
 * cannot be acquired because another request holds it.
 *
 * <p>This is Phase 2 of the 5-phase booking flow (Section 7.2).
 * The lock holder is executing an ACID transaction; this request fails-fast
 * with HTTP 409 in &lt; 5ms rather than waiting for the database.
 *
 * <p>Maps to HTTP 409 Conflict at the web layer.
 */
public class ResourceLockedException extends RuntimeException {

    public ResourceLockedException(String lockKey) {
        super("Resource is currently locked by a competing request. Lock key: " + lockKey);
    }
}
