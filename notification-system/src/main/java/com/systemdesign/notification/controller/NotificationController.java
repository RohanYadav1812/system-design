package com.systemdesign.notification.controller;

import com.systemdesign.notification.model.NotificationRequest;
import com.systemdesign.notification.service.NotificationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @PostMapping
    public ResponseEntity<Map<String, String>> send(
            @Valid @RequestBody NotificationRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        String id = notificationService.send(request, Optional.ofNullable(idempotencyKey));
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(Map.of("id", id, "status", "accepted"));
    }
}
