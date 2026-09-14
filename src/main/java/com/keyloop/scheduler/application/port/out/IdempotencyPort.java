package com.keyloop.scheduler.application.port.out;

import io.smallrye.mutiny.Uni;
import java.util.Optional;

/**
 * Output port for idempotency key management via Redis.
 *
 * <p>FR-03: Duplicate Request Handling — identical idempotency keys
 * receive matching responses within a 24-hour sliding window.
 */
public interface IdempotencyPort {
    /**
     * Checks if an idempotency key was already processed.
     *
     * @param idempotencyKey the client-supplied UUID key
     * @return cached JSON response string if exists, empty otherwise
     */
    Uni<Optional<String>> findCachedResponse(String idempotencyKey);

    /**
     * Stores the response for a given idempotency key.
     *
     * @param idempotencyKey the client-supplied UUID key
     * @param jsonResponse   the serialized response to cache
     * @param ttlSeconds     cache TTL (typically 86400 = 24h)
     */
    Uni<Void> cacheResponse(String idempotencyKey, String jsonResponse, int ttlSeconds);
}
