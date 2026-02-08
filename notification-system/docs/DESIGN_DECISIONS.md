# Notification System - Design Decisions

## 1. Message Queue: Kafka vs SQL Table (Polling)

### Why Kafka?
- **Decoupling**: Producers don't wait for consumers
- **Buffering**: Handle traffic spikes (1M notifications in 1 min)
- **Replay**: Re-process failed notifications
- **Partitioning**: Order per user_id (user sees notifications in order)

### Why NOT SQL table with polling?
- Polling adds latency
- Table locks under high write load
- No built-in replay
- Database becomes bottleneck

## 2. Per-Channel Topics vs Single Topic

| Approach | Pros | Cons |
|----------|------|------|
| **Single topic** | Simple, one consumer group | All consumers get all messages, filter in app |
| **Per-channel** (email, push, sms) | Consumer only gets its type, scale independently | More topics to manage |

**Chosen**: Single topic with `channel` field. Consumer filters. Simpler. Can split later if needed.

## 3. Idempotency: Redis vs Database

**Redis**: Fast, TTL (24h). Key = `idempotency:{client_key}` -> `notification_id`

**Why Redis**: Sub-ms lookup. Idempotency keys are short-lived (24h). Don't need persistence.

**Why NOT DB**: Adds 5-10ms per request. Table would grow unbounded without cleanup.

## 4. Delivery Status: Eventual vs Sync

**Async (chosen)**: API returns 202 immediately. Client polls status or gets webhook.

**Sync**: Wait for delivery. Would timeout (email can take 30s). Not feasible for scale.
