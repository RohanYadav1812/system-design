package com.systemdesign.ratelimiter.service;

import com.systemdesign.ratelimiter.config.RateLimiterProperties;
import com.systemdesign.ratelimiter.config.RateLimiterProperties.EndpointConfig;
import com.systemdesign.ratelimiter.model.RateLimitResult;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.Map;
import java.util.Optional;

@Service
public class RateLimiterService {

    private final Map<String, RateLimiter> limiters;
    private final RateLimiterProperties properties;

    public RateLimiterService(Map<String, RateLimiter> limiters, RateLimiterProperties properties) {
        this.limiters = limiters;
        this.properties = properties;
    }

    public RateLimitResult checkLimit(String clientId, String path) {
        if (!properties.isEnabled()) {
            return RateLimitResult.allowed(999, System.currentTimeMillis() / 1000 + 60);
        }

        EndpointConfig config = resolveConfig(path);
        String fullKey = buildKey(clientId, path);
        RateLimiter limiter = limiters.getOrDefault(config.getAlgorithm(), limiters.get("token-bucket"));

        return limiter.checkLimit(fullKey, config);
    }

    private EndpointConfig resolveConfig(String path) {
        Optional<Map.Entry<String, EndpointConfig>> match = properties.getEndpoints().entrySet().stream()
                .filter(e -> pathMatches(path, e.getKey()))
                .max(Comparator.comparing(e -> e.getKey().length()));

        return match.map(Map.Entry::getValue).orElse(properties.getDefaultConfig());
    }

    private boolean pathMatches(String path, String pattern) {
        if (pattern.endsWith("/*")) {
            String prefix = pattern.substring(0, pattern.length() - 2);
            return path.startsWith(prefix);
        }
        return path.equals(pattern);
    }

    private String buildKey(String clientId, String path) {
        return clientId + ":" + path.replace("/", ":");
    }
}
