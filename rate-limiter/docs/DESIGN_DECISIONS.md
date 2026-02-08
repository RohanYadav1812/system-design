# Rate Limiter - Design Decisions: Why, How, When, Why Not

## 1. Data Store: Redis vs SQL vs NoSQL

### Why Redis?
| Factor | Redis | SQL (PostgreSQL) | Cassandra |
|--------|-------|-----------------|-----------|
| **Latency** | ~0.1ms | ~1-5ms | ~5-10ms |
| **Atomicity** | INCR, Lua scripts | Transactions | Lightweight transactions |
| **Use case** | Counters, rate limits | Persistent data | High write throughput |
| **Operations** | O(1) INCR, EXPIRE | SELECT + UPDATE | Read-before-write |

**When to use Redis**: Ephemeral counters, need <1ms, high throughput (100K+ ops/s per node)

**Why NOT SQL**: Rate limits are transient - no need for durability. SQL adds 5-10x latency. Writes would bottleneck.

**Why NOT Cassandra**: Overkill for simple key-value. Eventual consistency can allow bursts across replicas.

---

## 2. Algorithm: Token Bucket vs Sliding Window vs Fixed Window

### Token Bucket
- **How**: Bucket holds N tokens, refills at R tokens/sec. Request consumes 1 token.
- **When**: API rate limiting, burst tolerance needed
- **Why**: Smooth rate, allows short bursts (e.g., 10 requests in 100ms)
- **Why NOT**: Slightly more complex state (tokens + last_refill)

### Sliding Window Log
- **How**: Store timestamp of each request. Count requests in last N seconds.
- **When**: Strict "no more than N in window" (e.g., 5 login attempts per minute)
- **Why**: No boundary burst - precise
- **Why NOT**: Memory = O(requests). 1000 req/min = 1000 timestamps stored

### Fixed Window
- **How**: Count in current minute/hour. Reset at boundary.
- **When**: Loose limits, analytics
- **Why**: Simple, 2 Redis keys (count + window)
- **Why NOT**: Boundary burst - 100 at 00:59 + 100 at 01:01 = 200 in 2 sec

**Our choice**: Token Bucket for general API, Sliding Window for auth endpoints.

---

## 3. Fail Open vs Fail Closed

| Strategy | When Redis Fails | Use Case |
|----------|------------------|----------|
| **Fail Open** | Allow all requests | User-facing APIs, availability critical |
| **Fail Closed** | Deny all requests | Security-critical (auth, payment) |

**Configurable per endpoint** via `rate-limiter.fail-open: true`

---

## 4. Rate Limit Key Strategy

| Key Type | Format | Limit | Example |
|----------|--------|-------|---------|
| **User** | `user:{userId}:{endpoint}` | Per authenticated user | `user:123:api/search` |
| **IP** | `ip:{ip}:{endpoint}` | Per IP (unauthenticated) | `ip:1.2.3.4:api/login` |
| **API Key** | `key:{apiKey}:{endpoint}` | Per developer key | `key:abc123:api/data` |

**Tiered**: Apply IP limit first (100/min), then User limit (1000/min) when authenticated.

---

## 5. Multi-Region Consistency

**Problem**: User in US hits US Redis, user in EU hits EU Redis. Different limits?

**Options**:
- **Per-region** (chosen): Simple, low latency. User gets separate limits per region. Acceptable for most APIs.
- **Global Redis**: Single source of truth. Higher latency for cross-region.
- **Replication**: Redis CRDT. Complex, eventual consistency.

**When global**: Payment limits, account-level quotas. Use global Redis or database.
