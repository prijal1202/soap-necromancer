package dev.soapnecromancer.web;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Token buckets keyed by client. Each client may spend {@code perMinute} requests at once and
 * regains one every {@code 60 s / perMinute}. Pure and clock-injected so it is testable without
 * sleeping; {@link RateLimitFilter} applies it to HTTP requests.
 */
final class RateLimiter {
    /** Above this many tracked clients, idle (full) buckets are dropped; worst case, all are. */
    static final int MAX_TRACKED_CLIENTS = 10_000;

    private final int capacity;
    private final long nanosPerToken;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    RateLimiter(int perMinute) {
        if (perMinute <= 0)
            throw new IllegalArgumentException("perMinute must be positive: " + perMinute);
        this.capacity = perMinute;
        this.nanosPerToken = Duration.ofMinutes(1).toNanos() / perMinute;
    }

    /** Takes a token for {@code client}; returns zero if allowed, else how long until the next token. */
    Duration tryAcquire(String client, long nowNanos) {
        if (buckets.size() > MAX_TRACKED_CLIENTS) {
            buckets.values().removeIf(b -> b.isFull(nowNanos));
            if (buckets.size() > MAX_TRACKED_CLIENTS)
                buckets.clear();
        }
        return Duration.ofNanos(buckets.computeIfAbsent(client, k -> new Bucket(nowNanos)).take(nowNanos));
    }

    private final class Bucket {
        private double tokens = capacity;
        private long updatedNanos;

        Bucket(long nowNanos) {
            this.updatedNanos = nowNanos;
        }

        synchronized long take(long nowNanos) {
            refill(nowNanos);
            if (tokens >= 1) {
                tokens -= 1;
                return 0;
            }
            return (long) Math.ceil((1 - tokens) * nanosPerToken);
        }

        synchronized boolean isFull(long nowNanos) {
            refill(nowNanos);
            return tokens >= capacity;
        }

        private void refill(long nowNanos) {
            long elapsed = nowNanos - updatedNanos;
            if (elapsed > 0) {
                tokens = Math.min(capacity, tokens + (double) elapsed / nanosPerToken);
                updatedNanos = nowNanos;
            }
        }
    }
}
