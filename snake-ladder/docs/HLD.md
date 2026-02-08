# Snake & Ladder - High Level Design

## Overview

Real-time multiplayer Snake & Ladder game supporting **millions of concurrent games**. Uses WebSocket for low-latency updates and Redis for game state.

## Architecture

```
┌─────────────┐     ┌─────────────┐     ┌──────────────────────────────────┐
│   Clients   │────▶│  API/LB     │────▶│     Game Service                  │
│  (WebSocket)│     │             │     │  - Create/Join game               │
└─────────────┘     └─────────────┘     │  - Roll dice, move                │
      │                    │            │  - State in Redis                  │
      │                    │            └──────────────┬─────────────────────┘
      │                    │                           │
      └────────────────────┼───────────────────────────┘
                           ▼
                    ┌─────────────┐
                    │   Redis     │
                    │  - Game     │
                    │    state    │
                    │  - Pub/Sub  │
                    │    events   │
                    └─────────────┘
```

## Flow Chart

```mermaid
flowchart TD
    A[Create Game] --> B[Generate game_id]
    B --> C[Store in Redis: game:{id}]
    C --> D[Return game_id + join URL]
    
    E[Join Game] --> F{Game exists?}
    F -->|No| G[404]
    F -->|Yes| H{Slot available?}
    H -->|No| I[409 Full]
    H -->|Yes| J[Add player via Redis HSET]
    J --> K[Pub/Sub: player_joined]
    
    L[Roll Dice] --> M{Player turn?}
    M -->|No| N[403 Not your turn]
    M -->|Yes| O[Random 1-6]
    O --> P[Calculate new position]
    P --> Q{Snake/Ladder?}
    Q -->|Yes| R[Apply and update]
    Q -->|No| R
    R --> S[Pub/Sub: position_update]
    S --> T{Win?}
    T -->|Yes| U[Pub/Sub: game_over]
```

## Design Decisions: Why Redis?

| Aspect | Redis | SQL | In-Memory |
|--------|-------|-----|-----------|
| **Game state** | HSET, fast | Transactions | Lost on restart |
| **Pub/Sub** | Native | Polling | Only single node |
| **TTL** | EXPIRE for abandoned games | Cron job | Manual |
| **Scale** | Cluster, shard by game_id | DB bottleneck | Sticky sessions |

**Why Redis**: Game state is ephemeral. Need Pub/Sub for real-time. Sub-ms reads. TTL for cleanup.
