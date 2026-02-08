package com.systemdesign.ratelimiter.service.impl;

import com.systemdesign.ratelimiter.config.RateLimiterProperties;
import com.systemdesign.ratelimiter.config.RateLimiterProperties.EndpointConfig;
import com.systemdesign.ratelimiter.model.RateLimitResult;
import com.systemdesign.ratelimiter.service.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Sliding Window Log algorithm - stores request timestamps.
 * Precise for strict limits (e.g., 5 login attempts per minute).
 * Memory: O(requests in window).
 */
@Service("sliding-window")
public class SlidingWindowRateLimiter implements RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(SlidingWindowRateLimiter.class);

    private static final String LUA_SCRIPT = """
            local key = KEYS[1]
            local now = tonumber(ARGV[1])
            local window_sec = tonumber(ARGV[2])
            local max_requests = tonumber(ARGV[3])
            local ttl_sec = window_sec + 1
            
            redis.call('ZREMRANGEBYSCORE', key, 0, now - window_sec)
            local count = redis.call('ZCARD', key)
            
            if count < max_requests then
                redis.call('ZADD', key, now, now .. '-' .. math.random())
                redis.call('EXPIRE', key, ttl_sec)
                count = count + 1
                return {1, max_requests - count, now + window_sec}
            else
                local oldest = redis.call('ZRANGE', key, 0, 0, 'WITHSCORES')
                local retry_after = 1
                if #oldest > 0 then
                    retry_after = math.ceil((tonumber(oldest[2]) + window_sec) - now)
                    retry_after = math.max(1, retry_after)
                end
                return {0, 0, now + retry_after}
            end
            """;

    private final RedisTemplate<String, String> redisTemplate;
    private final RateLimiterProperties properties;
    private final DefaultRedisScript<List> script;

    public SlidingWindowRateLimiter(RedisTemplate<String, String> redisTemplate,
                                    RateLimiterProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.script = new DefaultRedisScript<>(LUA_SCRIPT, List.class);
    }

    @Override
    public RateLimitResult checkLimit(String key, EndpointConfig config) {
        String redisKey = properties.getKeyPrefix() + ":" + key + ":sw";
        long now = System.currentTimeMillis() / 1000;

        try {
            @SuppressWarnings("unchecked")
            List<Long> result = (List<Long>) redisTemplate.execute(
                    script,
                    List.of(redisKey),
                    String.valueOf(now),
                    String.valueOf(config.getWindowSeconds()),
                    String.valueOf(config.getMaxRequests())
            );

            if (result == null || result.isEmpty()) {
                return handleRedisError(config);
            }

            boolean allowed = result.get(0) == 1;
            int remaining = result.get(1).intValue();
            long resetAt = result.get(2);

            if (allowed) {
                return RateLimitResult.allowed(remaining, resetAt);
            } else {
                long retryAfter = resetAt - now;
                return RateLimitResult.denied(Math.max(1, retryAfter), resetAt);
            }
        } catch (Exception e) {
            log.warn("Redis error in sliding window rate limiter: {}", e.getMessage());
            return handleRedisError(config);
        }
    }

    private RateLimitResult handleRedisError(EndpointConfig config) {
        if (properties.isFailOpen()) {
            return RateLimitResult.allowed(config.getMaxRequests() - 1, System.currentTimeMillis() / 1000 + 60);
        } else {
            return RateLimitResult.denied(60, System.currentTimeMillis() / 1000 + 60);
        }
    }
}
