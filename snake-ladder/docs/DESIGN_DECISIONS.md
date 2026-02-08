# Snake & Ladder - Design Decisions

## 1. State Storage: Redis vs Database

**Redis (chosen)**: Game state is short-lived (30 min max). Fast read/write. Pub/Sub for real-time. TTL for abandoned games.

**Why NOT SQL**: Overkill for ephemeral data. Adds latency. No native Pub/Sub.

## 2. Real-time: WebSocket vs Polling vs SSE

**WebSocket (chosen)**: Full duplex, low latency. Push updates immediately.

**Why NOT Polling**: Wastes bandwidth, higher latency.

**Why NOT SSE**: One-way only. WebSocket allows client to send roll without extra request.

## 3. Game State Structure

```
game:{gameId} -> Hash
  players: JSON [{id, name, position}]
  turn: 0
  status: playing|finished
  winner: null
  created_at: timestamp
  TTL: 3600
```

## 4. Snake/Ladder Board

Predefined 10x10 board. Positions 1-100. Snakes and ladders as static mapping.
