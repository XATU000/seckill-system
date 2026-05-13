package com.luqiang.seckill.common;

/**
 * Token bucket rate limiter. Thread-safe, no external dependencies.
 * Each {@link #tryAcquire()} call consumes 1 token if available.
 */
public class TokenBucketRateLimiter {

    private final double ratePerSecond;
    private final double capacity;
    private double tokens;
    private long lastRefillNanos;

    public TokenBucketRateLimiter(double ratePerSecond) {
        this.ratePerSecond = ratePerSecond;
        this.capacity = ratePerSecond; // max burst = 1 second worth of tokens
        this.tokens = capacity;
        this.lastRefillNanos = System.nanoTime();
    }

    /**
     * Try to consume a single token. Returns true if allowed, false if rate-limited.
     */
    public synchronized boolean tryAcquire() {
        long now = System.nanoTime();
        double elapsed = (now - lastRefillNanos) / 1_000_000_000.0;
        tokens = Math.min(capacity, tokens + elapsed * ratePerSecond);
        lastRefillNanos = now;

        if (tokens >= 1.0) {
            tokens -= 1.0;
            return true;
        }
        return false;
    }
}
