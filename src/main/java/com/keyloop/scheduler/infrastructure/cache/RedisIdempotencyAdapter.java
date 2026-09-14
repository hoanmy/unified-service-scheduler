package com.keyloop.scheduler.infrastructure.cache;

import com.keyloop.scheduler.application.port.out.IdempotencyPort;
import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.value.ReactiveValueCommands;
import io.quarkus.redis.datasource.value.SetArgs;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.Optional;

/**
 * Redis-backed idempotency adapter.
 *
 * <p>FR-03: Duplicate Request Handling — identical {@code Idempotency-Key} headers
 * receive matching responses within a 24-hour sliding window.
 *
 * <p>Key scheme: {@code idempotency:<UUID>}
 * Value: JSON-serialized response payload
 * TTL: 86400 seconds (24 hours, configurable)
 *
 * <p>Phase 1 of the 5-phase booking flow: if the key exists, the cached
 * {@code 201 Created} response is returned in &lt; 5ms without any DB operation.
 */
@ApplicationScoped
public class RedisIdempotencyAdapter implements IdempotencyPort {

    private static final Logger LOG = Logger.getLogger(RedisIdempotencyAdapter.class);
    private static final String KEY_PREFIX = "idempotency:";

    @Inject
    ReactiveRedisDataSource redisDataSource;

    private ReactiveValueCommands<String, String> valueCommands() {
        return redisDataSource.value(String.class);
    }

    @Override
    public Uni<Optional<String>> findCachedResponse(String idempotencyKey) {
        String redisKey = KEY_PREFIX + idempotencyKey;
        return valueCommands()
            .get(redisKey)
            .map(value -> {
                if (value != null) {
                    LOG.debugf("Idempotency cache HIT: %s", idempotencyKey);
                    return Optional.of(value);
                }
                return Optional.<String>empty();
            })
            .onFailure().invoke(ex ->
                LOG.warnf("Redis error on idempotency GET for key %s: %s", idempotencyKey, ex.getMessage()))
            .onFailure().recoverWithItem(Optional.empty());
    }

    @Override
    public Uni<Void> cacheResponse(String idempotencyKey, String jsonResponse, int ttlSeconds) {
        String redisKey = KEY_PREFIX + idempotencyKey;
        return valueCommands()
            .set(redisKey, jsonResponse, new SetArgs().ex(Duration.ofSeconds(ttlSeconds)))
            .replaceWithVoid()
            .onFailure().invoke(ex ->
                LOG.errorf("Failed to cache idempotency response for key %s: %s", idempotencyKey, ex.getMessage()))
            .onFailure().recoverWithNull().replaceWithVoid();
    }
}
