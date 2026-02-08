package com.systemdesign.ratelimiter.service;

import com.systemdesign.ratelimiter.config.RateLimiterProperties.EndpointConfig;
import com.systemdesign.ratelimiter.model.RateLimitResult;

/**
 * Rate limiting interface supporting multiple algorithms.
 * Implementations must be thread-safe and handle Redis failures gracefully.
 */
public interface RateLimiter {

    /**
     * Checks if the request is within rate limit.
     *
     * @param key    Unique identifier (e.g., userId:endpoint or ip:endpoint)
     * @param config Endpoint-specific configuration
     * @return Result indicating allowed/denied, remaining tokens, retry-after
     */
    RateLimitResult checkLimit(String key, EndpointConfig config);
}
