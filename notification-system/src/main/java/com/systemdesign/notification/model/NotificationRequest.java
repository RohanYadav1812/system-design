package com.systemdesign.notification.model;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

public record NotificationRequest(
        @NotBlank String userId,
        @NotNull Channel channel,
        @NotBlank String title,
        String body,
        @Email String email,
        String phone,
        Map<String, String> metadata
) {
    public enum Channel { EMAIL, PUSH, SMS }
}
