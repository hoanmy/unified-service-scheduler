package com.keyloop.scheduler.infrastructure.cache;

import com.keyloop.scheduler.application.port.out.DistributedLockPort;
import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.value.ReactiveValueCommands;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;

/**
 * Redis-backed distributed lock adapter.
 *
 * <p>Uses atomic {@code SET key value NX PX ttl} (SETNX equivalent) for
 * non-blocking lock acquisition. If the key already exists, the SET command
 * returns null — indicating the lock is held by another request.
 *
 * <p>This is Phase 2 of the 5-phase booking flow (Section 7.2, Section 8.1).
 * At 100k RPS, this layer absorbs 99.99% of contention, allowing only
 * one request per slot to reach the PostgreSQL ACID transaction layer.
 *
 * <p>For production at Keyloop scale, consider upgrading to Redlock
 * (multi-node consensus across 3-5 Redis masters) for higher availability.
 */
@ApplicationScoped
public class RedisDistributedLockAdapter implements DistributedLockPort {

    private static final Logger LOG = Logger.getLogger(RedisDistributedLockAdapter.class);
    private static final String LOCK_VALUE = "1"; // Presence indicates lock is held

    @Inject
    ReactiveRedisDataSource redisDataSource;

    private ReactiveValueCommands<String, String> valueCommands() {
        return redisDataSource.value(String.class);
    }

    @Override
    public Uni<Boolean> tryAcquire(String lockKey, int ttlSeconds) {
        // SET lockKey "1" NX EX ttlSeconds
        // Returns "OK" if set (lock acquired), null if key already exists (lock held)
        return valueCommands()
            .set(lockKey, LOCK_VALUE,
                new io.quarkus.redis.datasource.value.SetArgs()
                    .nx()
                    .ex(Duration.ofSeconds(ttlSeconds)))
            .map(result -> {
                boolean acquired = "OK".equals(result);
                if (!acquired) {
                    LOG.debugf("Lock contention on key: %s", lockKey);
                }
                return acquired;
            });
    }

    @Override
    public Uni<Void> release(String lockKey) {
        return valueCommands()
            .getdel(lockKey)
            .replaceWithVoid()
            .onFailure().invoke(ex ->
                LOG.warnf("Failed to release lock key %s: %s", lockKey, ex.getMessage()));
    }
}
