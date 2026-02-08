# Distributed Cache - API Flow & Step-by-Step Guide

## Design: Cache-Aside Pattern

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/cache/{key}` | Get value (L1 → L2 → DB) |
| PUT | `/api/cache/{key}` | Set value (L1 + L2) |
| DELETE | `/api/cache/{key}` | Invalidate (L1 + L2) |

## Request Flow - GET (Cache-Aside)

```mermaid
sequenceDiagram
    participant C as Client
    participant API as Cache API
    participant L1 as Caffeine (L1)
    participant L2 as Redis (L2)
    participant DB as Database

    C->>API: GET /api/cache/user:123
    API->>L1: get("user:123")
    alt L1 hit
        L1-->>API: value
        API-->>C: 200 {value}
    else L1 miss
        API->>L2: GET user:123
        alt L2 hit
            L2-->>API: value
            API->>L1: put("user:123", value)
            API-->>C: 200 {value}
        else L2 miss
            API->>DB: SELECT
            DB-->>API: value
            API->>L2: SET user:123 (TTL)
            API->>L1: put("user:123", value)
            API-->>C: 200 {value}
        end
    end
```

## Step-by-Step GET Flow

| Step | Component | Action |
|------|-----------|--------|
| 1 | Client | GET /cache/{key} |
| 2 | CacheService | L1 (Caffeine) get |
| 3 | If miss | L2 (Redis) get |
| 4 | If miss | Backend/DB load |
| 5 | Populate | SET in L2, put in L1 |
| 6 | Return | Value to client |
