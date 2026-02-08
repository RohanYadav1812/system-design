# System Design - API Overview

## Quick Reference

| System | Base Port | Key APIs |
|--------|-----------|----------|
| [Rate Limiter](../rate-limiter/docs/API_FLOW.md) | 8080 | GET /api/search, POST /api/login |
| [Notification](../notification-system/docs/API_FLOW.md) | 8081 | POST /api/notifications |
| [Snake & Ladder](../snake-ladder/docs/API_FLOW.md) | 8082 | POST /api/games, /join, /roll |
| [Logging](../logging-service/docs/API_FLOW.md) | 8083 | POST /api/logs |

## API Flow Documentation

Each system has detailed API flow documentation:

- **[Rate Limiter API Flow](../rate-limiter/docs/API_FLOW.md)** - Request flow, client identification, headers, step-by-step
- **[Notification API Flow](../notification-system/docs/API_FLOW.md)** - Send flow, idempotency, Kafka publish
- **[Snake & Ladder API Flow](../snake-ladder/docs/API_FLOW.md)** - Create, join, roll, game state
- **[Logging API Flow](../logging-service/docs/API_FLOW.md)** - Ingest flow, partitioning, consumer

## Common Patterns

1. **Async (202 Accepted)** - Notification, Logging return immediately, process in background
2. **Idempotency** - Notification uses Idempotency-Key header
3. **Rate Limit Headers** - X-RateLimit-Remaining, Retry-After
4. **Config via Env** - All services use `${VAR}` in application.yml
