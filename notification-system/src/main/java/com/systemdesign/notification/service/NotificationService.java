package com.systemdesign.notification.service;

import com.systemdesign.notification.model.NotificationRequest;

import java.util.Optional;

public interface NotificationService {

    String send(NotificationRequest request, Optional<String> idempotencyKey);
}
