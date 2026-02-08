# Logging Service - High Level Design

## Overview

Centralized log aggregation for **millions of events per second**. Collects logs from distributed services, persists to storage, enables search and analytics.

## Architecture

```
┌─────────────┐ ┌─────────────┐ ┌─────────────┐     ┌─────────────────────┐
│  Service A  │ │  Service B  │ │  Service C  │     │   Log Ingestion API   │
│  (SDK/Agent)│ │             │ │             │────▶│   - Validate          │
└─────────────┘ └─────────────┘ └─────────────┘     │   - Batch & Publish   │
                                                   └───────────┬───────────┘
                                                               │
                                                               ▼
                                                    ┌─────────────────────┐
                                                    │  Kafka (logs topic)  │
                                                    │ 分区: service_id      │
                                                    └───────────┬───────────┘
                                                               │
                                    ┌──────────────────────────┼──────────────────────────┐
                                    ▼                          ▼                          ▼
                            ┌───────────────┐          ┌───────────────┐          ┌───────────────┐
                            │ Log Consumer  │          │ Log Consumer  │          │ Log Consumer  │
                            │ (Elasticsearch)│          │ (S3 cold)     │          │ (Alerts)      │
                            └───────────────┘          └───────────────┘          └───────────────┘
```

## Flow Chart

```mermaid
flowchart TD
    A[Client sends log] --> B{Valid?}
    B -->|No| C[400]
    B -->|Yes| D[Add metadata: timestamp, service_id]
    D --> E[Batch in buffer]
    E --> F{Buffer full or timeout?}
    F -->|No| G[Wait]
    G --> E
    F -->|Yes| H[Publish to Kafka]
    H --> I[Return 202]
    
    J[Consumer] --> K[Deserialize]
    K --> L[Index to Elasticsearch]
    L --> M{Error?}
    M -->|Yes| N[Retry/DLQ]
    M -->|No| O[ACK]
```

## Design Decisions: Why Kafka + Elasticsearch?

| Component | Why | Why Not |
|-----------|-----|---------|
| **Kafka** | Buffers bursts, replay, partition by service | SQS: No ordering. Direct to ES: Backpressure |
| **Elasticsearch** | Full-text search, time-range queries, Kibana | Solr: Similar. Cassandra: No full-text. S3: No search |
| **Hot/Warm** | Recent in ES (7d), older to S3 | All in ES: Cost. All in S3: No search |
