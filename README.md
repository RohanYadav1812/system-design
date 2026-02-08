# System Design Repository

A collection of production-ready system designs with High-Level Design (HLD), Low-Level Design (LLD), Java implementations, and comprehensive documentation. Each system is designed for **millions to billions of users** with scalability, fault tolerance, and edge case handling.

## Design Principles

- **Production Ready**: Full configuration, no hardcoding, proper error handling
- **Scalable**: Multi-region, horizontal scaling, distributed architecture
- **Documented**: Why, How, When, Why Not for every major decision
- **Interview Focused**: Covers common system design topics

## Systems Included

| System | Description | Key Tech |
|--------|-------------|----------|
| [Rate Limiter](./rate-limiter) | Token bucket, sliding window algorithms | Redis, Java |
| [Notification System](./notification-system) | Push, email, SMS at scale | Kafka, Redis |
| [Snake & Ladder](./snake-ladder) | Distributed multiplayer game | Redis, WebSocket |
| [Logging Service](./logging-service) | Centralized log aggregation | Kafka, Elasticsearch |
| [URL Shortener](./url-shortener) | TinyURL-style service | Cassandra, Redis |
| [Distributed Cache](./distributed-cache) | High-throughput caching layer | Redis Cluster |

## Repository Structure

```
system-design/
├── rate-limiter/           # Rate limiting at scale
├── notification-system/    # Multi-channel notifications
├── snake-ladder/           # Real-time multiplayer game
├── logging-service/       # Centralized logging
├── url-shortener/         # URL shortening service
├── distributed-cache/     # Distributed caching
└── docs/                  # Shared design patterns
```

## Quick Start

```bash
# Start dependencies (Redis, Kafka)
docker-compose up -d

# Build all modules
mvn clean install

# Run specific system
cd rate-limiter && mvn spring-boot:run
```

## Design Document Format

Each system contains:
- **HLD** - High-Level Design with architecture diagrams
- **LLD** - Low-Level Design with class diagrams
- **Flow Charts** - Request flows, decision trees
- **Design Decisions** - SQL vs NoSQL, technology choices
- **Edge Cases** - Handling and mitigation
- **Scalability** - Sharding, replication strategies

## Requirements

- Java 17+
- Maven 3.8+
- Docker (for Redis, Kafka, etc.)

## License

MIT License
