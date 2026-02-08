package com.systemdesign.notification.service.impl;

import com.systemdesign.notification.model.NotificationRequest;
import com.systemdesign.notification.service.NotificationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class NotificationServiceImpl implements NotificationService {

    private final KafkaTemplate<String, NotificationRequest> kafkaTemplate;
    private final RedisTemplate<String, String> redisTemplate;
    private final String topic;
    private final long idempotencyTtlSeconds;

    public NotificationServiceImpl(KafkaTemplate<String, NotificationRequest> kafkaTemplate,
                                   RedisTemplate<String, String> redisTemplate,
                                   @Value("${notification.topic}") String topic,
                                   @Value("${notification.idempotency-ttl-seconds:86400}") long idempotencyTtlSeconds) {
        this.kafkaTemplate = kafkaTemplate;
        this.redisTemplate = redisTemplate;
        this.topic = topic;
        this.idempotencyTtlSeconds = idempotencyTtlSeconds;
    }

    @Override
    public String send(NotificationRequest request, Optional<String> idempotencyKey) {
        if (idempotencyKey.isPresent()) {
            String key = "idempotency:" + idempotencyKey.get();
            String existing = redisTemplate.opsForValue().get(key);
            if (existing != null) {
                return existing;
            }
        }

        String notificationId = UUID.randomUUID().toString();
        kafkaTemplate.send(topic, request.userId(), request);

        if (idempotencyKey.isPresent()) {
            redisTemplate.opsForValue().set("idempotency:" + idempotencyKey.get(),
                    notificationId, idempotencyTtlSeconds, TimeUnit.SECONDS);
        }

        return notificationId;
    }
}
