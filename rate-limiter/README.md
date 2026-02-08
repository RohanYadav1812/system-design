# Rate Limiter

Distributed rate limiting with Token Bucket and Sliding Window algorithms. Designed for millions of requests per second.

## Features

- **Token Bucket**: Burst-tolerant, smooth rate limiting
- **Sliding Window**: Strict limits (e.g., 5 login attempts per minute)
- **Configurable per endpoint** via `application.yml`
- **Fail open/closed** when Redis is unavailable
- **Standard headers**: X-RateLimit-Remaining, Retry-After

## Quick Start

```bash
# Start Redis
docker run -d -p 6379:6379 redis:7-alpine

# Run service
mvn spring-boot:run

# Test
curl http://localhost:8080/api/search
```

## Design Docs

- [HLD](./docs/HLD.md) - Architecture, flowcharts
- [LLD](./docs/LLD.md) - Class diagram, Redis key design
- [Design Decisions](./docs/DESIGN_DECISIONS.md) - Why Redis, why Token Bucket, etc.
