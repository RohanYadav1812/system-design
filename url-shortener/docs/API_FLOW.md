# URL Shortener - API Flow & Step-by-Step Guide

## API Endpoints (Design)

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/shorten` | Create short URL |
| GET | `/{shortCode}` | Redirect to long URL |

## Request Flow - Create Short URL

```mermaid
sequenceDiagram
    participant C as Client
    participant API as Shortener API
    participant R as Redis Cache
    participant DB as Database

    C->>API: POST /api/shorten {longUrl, customSlug?}
    API->>API: Validate URL
    alt Custom slug provided
        API->>DB: SELECT where shortCode=slug
        alt Exists
            API-->>C: 409 Conflict
        else Available
            API->>DB: INSERT mapping
            API->>R: SET shortCode->longUrl (cache)
            API-->>C: 201 {{baseUrl}}/abc123
        end
    else Auto-generate
        API->>API: Generate base62(id) or hash
        API->>DB: INSERT mapping
        API->>R: SET shortCode->longUrl
        API-->>C: 201 {{baseUrl}}/xyz789
    end
```

## Request Flow - Redirect

```mermaid
sequenceDiagram
    participant C as Client
    participant API as Redirect Handler
    participant R as Redis
    participant DB as Database

    C->>API: GET /xyz789
    API->>R: GET xyz789
    alt Cache hit
        R-->>API: longUrl
        API-->>C: 302 Redirect
    else Cache miss
        R-->>API: null
        API->>DB: SELECT longUrl WHERE shortCode=xyz789
        API->>R: SET xyz789->longUrl (populate cache)
        API-->>C: 302 Redirect
    end
```

## Step-by-Step Summary

| Step | Create | Redirect |
|------|--------|----------|
| 1 | Validate long URL | Extract shortCode from path |
| 2 | Check custom slug availability | Redis GET shortCode |
| 3 | Generate or use slug | Cache miss → DB lookup |
| 4 | DB INSERT | Populate Redis cache |
| 5 | Redis SET (write-through) | 302 Location: longUrl |
| 6 | Return short URL | - |
