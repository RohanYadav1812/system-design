# Notification System

Multi-channel (Email, Push, SMS) notification system with Kafka for async processing. Idempotent sends.

## Features

- Async processing via Kafka
- Idempotency via Redis (Idempotency-Key header)
- Configurable via environment variables

## Design Docs

- [HLD](./docs/HLD.md)
- [Design Decisions](./docs/DESIGN_DECISIONS.md)
