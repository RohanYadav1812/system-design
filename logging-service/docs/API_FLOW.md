# Logging Service - API Flow & Step-by-Step Guide

## API Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/logs` | Ingest log entry (async, returns 202) |

## Request Flow - Step by Step

### Step 1: Client Sends Log
```json
POST /api/logs
Content-Type: application/json

{
  "timestamp": 1739000000000,
  "level": "INFO",
  "service": "rate-limiter",
  "message": "Request processed successfully",
  "traceId": "abc-123",
  "metadata": {
    "userId": "u1",
    "latencyMs": 45
  }
}
```

### Step 2: Validation
```
LogController.ingest()
  ├─ @Valid → Validate request (timestamp, level, service, message required)
  └─ logIngestionService.ingest(entry)
```

### Step 3: Publish to Kafka
```
KafkaLogIngestionService.ingest()
  ├─ Kafka key = entry.service (partition by service)
  ├─ Kafka value = LogEntry JSON
  ├─ KafkaTemplate.send(topic, service, entry)
  └─ Returns immediately (async)
```

### Step 4: Response
```
Controller → 202 Accepted
Body: {"status": "accepted"}
```

## Complete Flow Diagram

```mermaid
sequenceDiagram
    autonumber
    participant S as Service (Source)
    participant API as Log API
    participant K as Kafka
    participant C as Consumer
    participant ES as Elasticsearch

    S->>API: POST /api/logs (LogEntry)
    API->>API: Validate
    API->>K: send(topic=logs, key=service, value=entry)
    K-->>API: ack
    API-->>S: 202 Accepted

    Note over K,C: Async consumer pipeline
    K->>C: Poll batch
    C->>C: Deserialize
    C->>ES: Index document
    ES-->>C: ack
    C->>K: Commit offset
```

## Step-by-Step Summary

| Step | Component | Action |
|------|-----------|--------|
| 1 | Source Service | POST log JSON to /api/logs |
| 2 | LogController | Validate timestamp, level, service, message |
| 3 | KafkaLogIngestionService | KafkaTemplate.send(service, entry) |
| 4 | Kafka | Partition by service_id, store |
| 5 | Controller | Return 202 immediately |
| 6 | Consumer | (Async) Consume, index to Elasticsearch |

## Log Entry Schema

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| timestamp | long | Yes | Unix ms |
| level | string | Yes | INFO, WARN, ERROR, DEBUG |
| service | string | Yes | Service name (partition key) |
| message | string | Yes | Log message |
| traceId | string | No | Distributed tracing ID |
| metadata | object | No | Additional key-value pairs |

## Partitioning Strategy

```
Kafka topic: logs
Partition key: service
→ Logs from same service go to same partition
→ Maintains order per service
→ Enables parallel consumption by service
```

## Downstream Consumer Flow

```mermaid
flowchart TD
    A[Kafka Consumer] --> B[Deserialize LogEntry]
    B --> C[Enrich: add host, region]
    C --> D[Index to Elasticsearch]
    D --> E{Success?}
    E -->|Yes| F[ACK offset]
    E -->|No| G[Retry 3x]
    G --> H{Fail?}
    H -->|Yes| I[Dead Letter Queue]
    H -->|No| F
```
