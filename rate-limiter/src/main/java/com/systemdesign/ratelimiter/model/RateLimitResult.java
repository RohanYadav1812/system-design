package com.systemdesign.ratelimiter.model;

public record RateLimitResult(
        boolean allowed,
        int remaining,
        long retryAfterSeconds,
        long resetAt
) {
    public static RateLimitResult allowed(int remaining, long resetAt) {
        return new RateLimitResult(true, remaining, 0, resetAt);
    }

    public static RateLimitResult denied(long retryAfterSeconds, long resetAt) {
        return new RateLimitResult(false, 0, retryAfterSeconds, resetAt);
    }
}
