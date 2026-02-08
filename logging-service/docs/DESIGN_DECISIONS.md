# Logging Service - Design Decisions

## 1. Ingestion: Sync vs Async

**Async (Kafka)**: API accepts, returns 202. Kafka buffers. Handles 1M+ events/sec.

**Why NOT sync to DB**: Would block. DB would be bottleneck. No buffering for spikes.

## 2. Storage: Elasticsearch vs Cassandra vs S3

| Storage | Use Case | Latency |
|---------|----------|---------|
| **Elasticsearch** | Last 7 days, search | ms |
| **S3** | Archive, cold storage | seconds |
| **Cassandra** | Time-series, no full-text | ms |

**Elasticsearch**: Full-text search, aggregations, Kibana dashboards. Industry standard for log analytics.

## 3. Log Format

```json
{
  "timestamp": "2026-02-08T10:00:00Z",
  "level": "INFO",
  "service": "rate-limiter",
  "message": "Request processed",
  "trace_id": "abc123",
  "metadata": {}
}
```

Standard fields for filtering. Structured JSON for parsing.
