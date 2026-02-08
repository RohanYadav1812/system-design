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
 * Token Bucket algorithm implementation using Redis Lua for atomicity.
 * Handles burst traffic while maintaining sustained rate.
 */
@Service("token-bucket")
public class TokenBucketRateLimiter implements RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(TokenBucketRateLimiter.class);

    private static final String LUA_SCRIPT = """
            local key = KEYS[1]
            local ts_key = KEYS[2]
            local capacity = tonumber(ARGV[1])
            local refill_rate = tonumber(ARGV[2])
            local now = tonumber(ARGV[3])
            local ttl_ms = tonumber(ARGV[4])
            
            local tokens = tonumber(redis.call('GET', key) or capacity)
            local last_refill = tonumber(redis.call('GET', ts_key) or now)
            
            local elapsed = now - last_refill
            tokens = math.min(capacity, tokens + elapsed * refill_rate)
            last_refill = now
            
            if tokens >= 1 then
                tokens = tokens - 1
                redis.call('SET', key, tokens, 'PX', ttl_ms)
                redis.call('SET', ts_key, tostring(last_refill), 'PX', ttl_ms)
                return {1, math.floor(tokens)}
            else
                return {0, 0}
            end
            """;

    private final RedisTemplate<String, String> redisTemplate;
    private final RateLimiterProperties properties;
    private final DefaultRedisScript<List> script;

    public TokenBucketRateLimiter(RedisTemplate<String, String> redisTemplate,
                                  RateLimiterProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.script = new DefaultRedisScript<>(LUA_SCRIPT, List.class);
    }

    @Override
    public RateLimitResult checkLimit(String key, EndpointConfig config) {
        String redisKey = properties.getKeyPrefix() + ":" + key + ":tb";
        String tsKey = redisKey + ":ts";
        long now = System.currentTimeMillis() / 1000;
        int ttlMs = config.getWindowSeconds() * 1000;

        try {
            @SuppressWarnings("unchecked")
            List<Long> result = (List<Long>) redisTemplate.execute(
                    script,
                    List.of(redisKey, tsKey),
                    String.valueOf(config.getCapacity()),
                    String.valueOf(config.getTokensPerSecond()),
                    String.valueOf(now),
                    String.valueOf(ttlMs)
            );

            if (result == null || result.isEmpty()) {
                return handleRedisError(config);
            }

            boolean allowed = result.get(0) == 1;
            int remaining = result.get(1).intValue();
            long resetAt = now + config.getWindowSeconds();

            if (allowed) {
                return RateLimitResult.allowed(remaining, resetAt);
            } else {
                long retryAfter = (long) Math.ceil(1.0 / config.getTokensPerSecond());
                return RateLimitResult.denied(retryAfter, resetAt);
            }
        } catch (Exception e) {
            log.warn("Redis error in rate limiter: {}", e.getMessage());
            return handleRedisError(config);
        }
    }

    private RateLimitResult handleRedisError(EndpointConfig config) {
        if (properties.isFailOpen()) {
            return RateLimitResult.allowed(config.getCapacity() - 1, System.currentTimeMillis() / 1000 + 60);
        } else {
            return RateLimitResult.denied(60, System.currentTimeMillis() / 1000 + 60);
        }
    }
}
