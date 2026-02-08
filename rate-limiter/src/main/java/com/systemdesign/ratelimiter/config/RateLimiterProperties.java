package com.systemdesign.ratelimiter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.HashMap;
import java.util.Map;

@ConfigurationProperties(prefix = "rate-limiter")
@Validated
public class RateLimiterProperties {

    private boolean enabled = true;
    private boolean failOpen = true;
    @NotBlank
    private String keyPrefix = "rl";
    @NotNull
    private EndpointConfig defaultConfig = new EndpointConfig();
    private Map<String, EndpointConfig> endpoints = new HashMap<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isFailOpen() {
        return failOpen;
    }

    public void setFailOpen(boolean failOpen) {
        this.failOpen = failOpen;
    }

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public void setKeyPrefix(String keyPrefix) {
        this.keyPrefix = keyPrefix;
    }

    public EndpointConfig getDefaultConfig() {
        return defaultConfig;
    }

    public void setDefaultConfig(EndpointConfig defaultConfig) {
        this.defaultConfig = defaultConfig;
    }

    public Map<String, EndpointConfig> getEndpoints() {
        return endpoints;
    }

    public void setEndpoints(Map<String, EndpointConfig> endpoints) {
        this.endpoints = endpoints;
    }

    public static class EndpointConfig {
        private String algorithm = "token-bucket";
        @Min(1)
        private int tokensPerSecond = 10;
        @Min(1)
        private int capacity = 100;
        @Min(1)
        private int maxRequests = 100;
        @Min(1)
        private int windowSeconds = 60;

        public String getAlgorithm() {
            return algorithm;
        }

        public void setAlgorithm(String algorithm) {
            this.algorithm = algorithm;
        }

        public int getTokensPerSecond() {
            return tokensPerSecond;
        }

        public void setTokensPerSecond(int tokensPerSecond) {
            this.tokensPerSecond = tokensPerSecond;
        }

        public int getCapacity() {
            return capacity;
        }

        public void setCapacity(int capacity) {
            this.capacity = capacity;
        }

        public int getMaxRequests() {
            return maxRequests;
        }

        public void setMaxRequests(int maxRequests) {
            this.maxRequests = maxRequests;
        }

        public int getWindowSeconds() {
            return windowSeconds;
        }

        public void setWindowSeconds(int windowSeconds) {
            this.windowSeconds = windowSeconds;
        }
    }
}
