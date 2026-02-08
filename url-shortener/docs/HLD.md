# URL Shortener - High Level Design

## Overview

TinyURL-style service for **billions of URLs**. Short code → Long URL mapping with analytics, custom slugs, and high read throughput.

## Architecture

```
Client → API → [Redis Cache] → [DB if miss] → Return
                ↑
         Write-through on create
```

## Design Decisions: Why Redis + SQL?

| Storage | Purpose | Why |
|---------|---------|-----|
| **Redis** | Hot cache for reads | 100K+ reads/sec, sub-ms |
| **SQL/Cassandra** | Persistent mapping | Durability, analytics |
| **Short code** | Base62 encoding | 6 chars = 56B combinations |

**Why NOT only Redis**: Durability. Redis can lose data. DB is source of truth.

**Why NOT only SQL**: Read latency. 10K reads/sec would overwhelm DB.
