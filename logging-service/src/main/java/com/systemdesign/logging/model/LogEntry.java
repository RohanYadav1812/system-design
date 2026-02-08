package com.systemdesign.logging.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

public record LogEntry(
        @NotNull Long timestamp,
        @NotBlank String level,
        @NotBlank String service,
        @NotBlank String message,
        String traceId,
        Map<String, Object> metadata
) {}
