package com.keyloop.scheduler.application.port.out;

import io.smallrye.mutiny.Uni;

/**
 * Output port for atomic distributed lock acquisition via Redis.
 *
 * <p>The implementation executes an atomic Lua script that:
 * <ol>
 *   <li>Checks idempotency key (returns cached response if exists)</li>
 *   <li>Checks if technician or bay lock is held</li>
 *   <li>Acquires both locks atomically with TTL</li>
 * </ol>
 */
public interface DistributedLockPort {
    /**
     * Attempts to acquire a named distributed lock.
     *
     * @param lockKey unique key (e.g., "lock:tech:{id}:{slot}")
     * @param ttlSeconds lock expiry (must exceed max ACID transaction duration)
     * @return Uni<Boolean> — true if lock acquired, false if already held
     */
    Uni<Boolean> tryAcquire(String lockKey, int ttlSeconds);

    /**
     * Releases a previously acquired lock.
     *
     * @param lockKey the lock key to release
     */
    Uni<Void> release(String lockKey);
}
