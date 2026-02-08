# Distributed Cache - High Level Design

## Overview

Multi-level caching: Local (Caffeine) + Distributed (Redis). Handles **millions of requests/sec** with cache-aside pattern.

## Architecture

```
Request → [L1: Caffeine] → Hit? Return
                ↓ Miss
           [L2: Redis] → Hit? Return, populate L1
                ↓ Miss
           [Backend/DB] → Store in L2, L1, Return
```

## Design Decisions: Why Tiered?

| Level | Latency | Capacity | Use |
|-------|---------|----------|-----|
| **L1** | ~100ns | 10K items | Hot keys, reduce Redis load |
| **L2** | ~1ms | Millions | Shared across instances |
