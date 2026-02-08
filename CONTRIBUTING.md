# Contributing & Setup

## Prerequisites

- Java 17+
- Maven 3.8+ (or use `./mvnw` wrapper)
- Docker & Docker Compose
- Git

## Local Development

### 1. Start Dependencies

```bash
docker-compose up -d
```

This starts:
- Redis on port 6379
- Zookeeper on port 2181
- Kafka on port 9092

### 2. Build

```bash
mvn clean install
```

### 3. Run Services

```bash
# Rate Limiter (requires Redis)
cd rate-limiter && mvn spring-boot:run

# Notification System (requires Redis + Kafka)
cd notification-system && mvn spring-boot:run

# Snake & Ladder (requires Redis)
cd snake-ladder && mvn spring-boot:run

# Logging Service (requires Kafka)
cd logging-service && mvn spring-boot:run
```

## Git - Push to GitHub

```bash
git init
git add .
git commit -m "Initial commit: System design implementations"
git branch -M main
git remote add origin git@github.com:YOUR_USERNAME/system-design.git
git push -u origin main
```

## Environment Variables

All services use environment variables for configuration. See `application.yml` in each module for defaults and variable names.
