package com.cobre.challenge.adapter.in.web.selfservice;

import com.cobre.challenge.adapter.in.web.selfservice.config.IdempotencyProperties;
import com.cobre.challenge.domain.model.tenant.TenantId;
import java.time.Clock;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

/**
 * Early HTTP-level rejection of a double click, on top of the DB-level guard
 * ({@code idx_deliveries_live_pair}, ADR-003 §2 / ADR-005 §1 / ADR-007 §6's closing note): if this
 * guard's entry is evicted, expired, or lost to a pod restart, the second replay still hits the
 * partial unique index and still becomes a 409 — the guard failing open is acceptable, the index
 * failing is not, and it cannot.
 *
 * <p><strong>Per-pod, like {@code ClientRateLimitFilter}</strong>: with N instances a repeat can
 * land on a pod that has never seen the key, and the DB-level guard does its job there. No table,
 * migration, Redis or other distributed cache — this is in-process only.
 *
 * <p><strong>Virtual-thread safety:</strong> the map is a synchronized, access-ordered
 * {@link LinkedHashMap}, exactly {@code ClientRateLimitFilter}'s bounded-map shape, so the only
 * {@code synchronized} sections are the trivial in-memory {@code get}/{@code put} below.
 * {@code action.get()} — the replay use case call — runs entirely outside any lock. This accepts
 * a narrow race (two concurrent requests for the same key can both miss the cache and both invoke
 * the use case) instead of holding a lock across that call, which would risk pinning a virtual
 * thread; the database's partial unique index closes that race regardless.
 */
@Component
@EnableConfigurationProperties(IdempotencyProperties.class)
public final class ReplayIdempotencyGuard {

    private final IdempotencyProperties properties;
    private final Clock clock;
    private final Map<Key, CachedResponse> entries;

    public ReplayIdempotencyGuard(IdempotencyProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        this.entries = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Key, CachedResponse> eldest) {
                return size() > properties.maxEntries();
            }
        });
    }

    /**
     * Returns the cached response for {@code (tenant, deliveryId, idempotencyKey)} if one is
     * still within its TTL, without invoking {@code action}; otherwise invokes {@code action} and
     * caches its result.
     */
    public ResponseEntity<Object> executeOrReplay(
            TenantId tenant, UUID deliveryId, String idempotencyKey, Supplier<ResponseEntity<Object>> action) {
        Key key = new Key(tenant, deliveryId, idempotencyKey);
        Instant now = clock.instant();

        CachedResponse cached = entries.get(key);
        if (cached != null && cached.expiresAt().isAfter(now)) {
            return cached.response();
        }

        ResponseEntity<Object> response = action.get();
        entries.put(key, new CachedResponse(response, now.plus(properties.ttl())));
        return response;
    }

    /** Test support only: the number of entries currently tracked in the bounded map. */
    int trackedEntryCount() {
        return entries.size();
    }

    private record Key(TenantId tenant, UUID deliveryId, String idempotencyKey) {
    }

    private record CachedResponse(ResponseEntity<Object> response, Instant expiresAt) {
    }
}
