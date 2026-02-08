# Notification System - High Level Design

## Overview

A distributed notification system supporting **Push, Email, SMS** channels. Designed for **billions of notifications** with 99.9% delivery, idempotency, and dead-letter handling.

## Architecture

```
┌─────────────┐     ┌─────────────┐     ┌──────────────────────────────────┐
│   Clients   │────▶│  API Gateway│────▶│     Notification Service          │
│  (Mobile,   │     │  + Auth     │     │  - Validate, Dedupe, Enrich       │
│   Web)      │     └─────────────┘     │  - Publish to Kafka               │
└─────────────┘                        └──────────────┬─────────────────────┘
                                                      │
                                                      ▼
                                            ┌─────────────────┐
                                            │ Kafka Topic      │
                                            │ notifications   │
                                            │ (partitioned by │
                                            │  user_id)       │
                                            └────────┬────────┘
                                                      │
                    ┌─────────────────────────────────┼─────────────────────────────────┐
                    ▼                                 ▼                                 ▼
            ┌───────────────┐               ┌───────────────┐               ┌───────────────┐
            │ Email Worker  │               │ Push Worker   │               │ SMS Worker    │
            │ Consumer      │               │ Consumer      │               │ Consumer      │
            └───────┬───────┘               └───────┬───────┘               └───────┬───────┘
                    │                               │                               │
                    ▼                               ▼                               ▼
            ┌───────────────┐               ┌───────────────┐               ┌───────────────┐
            │ SMTP / SES    │               │ FCM / APNs    │               │ Twilio / SNS  │
            └───────────────┘               └───────────────┘               └───────────────┘
```

## Flow Chart

```mermaid
flowchart TD
    A[Send Notification API] --> B{Valid?}
    B -->|No| C[400 Bad Request]
    B -->|Yes| D[Idempotency Check - Redis]
    D --> E{Duplicate?}
    E -->|Yes| F[Return existing id]
    E -->|No| G[Generate ID, Store in Redis]
    G --> H[Publish to Kafka]
    H --> I[Return 202 Accepted]
    
    J[Kafka Consumer] --> K[Deserialize]
    K --> L{Channel?}
    L -->|email| M[Email Sender]
    L -->|push| N[Push Sender]
    L -->|sms| O[SMS Sender]
    M --> P{Success?}
    N --> P
    O --> P
    P -->|Yes| Q[ACK, Update status]
    P -->|No| R[Retry 3x]
    R -->|Fail| S[Dead Letter Queue]
```

## Design Decisions: Why Kafka vs SQS vs RabbitMQ

| Aspect | Kafka | SQS | RabbitMQ |
|--------|-------|-----|----------|
| **Throughput** | Millions/sec | 3000/sec (standard) | ~50K/sec |
| **Ordering** | Per-partition | No | Per-queue |
| **Retention** | 7 days | 14 days | Until consumed |
| **Use case** | Event streaming, replay | Simple queue | Complex routing |
| **Why chosen** | Scale, replay, partitioning | - | - |

**Why Kafka**: Billions of notifications need partitioning by user_id for ordering. Replay for failed batches. High throughput.

**Why NOT SQS**: Lower throughput, no partitioning. Good for simpler workflows.

**Why NOT RabbitMQ**: Complex topology, lower scale. Good for complex routing.

## Edge Cases

| Edge Case | Handling |
|-----------|----------|
| Duplicate (same idempotency key) | Redis check, return existing |
| User opted out | Check preferences before send |
| Provider rate limit | Backoff, retry with exponential decay |
| Provider down | Dead letter, alert, manual retry |
| Malformed payload | Validate, reject with 400 |
| User deleted | Idempotent - skip gracefully |
