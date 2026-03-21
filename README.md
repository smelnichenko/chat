# Chat Service

Real-time messaging service for pmon.dev with channel-based chat and optional E2E encryption.

## Architecture

Receives authenticated requests via the API gateway. Uses Kafka as the message bus for persistence and delivery fan-out. Stores messages in ScyllaDB (day-bucketed partitions) and channel/member metadata in PostgreSQL. Delivers real-time messages over STOMP/WebSocket. Consumes Kafka `user.events` to sync users from the admin service.

```
API Gateway --> Chat (this service) --> PostgreSQL (monitor_chat DB, channels/members)
                                    --> ScyllaDB (messages, day-bucketed)
                                    --> Kafka (chat.messages, chat.events)
                                    --> Redis (presence, user cache)
            <-- WebSocket (STOMP/SockJS)
            <-- Kafka <-- Admin        (user event sync)
```

## Tech Stack

- Java 25, Spring Boot 4.0, Gradle 9.3
- PostgreSQL 17 (channels, members, user keys, key bundles)
- ScyllaDB 6.2 (message persistence, CQL via DataStax driver 4.17.0)
- Kafka 4.2 KRaft (message bus, 12 partitions for chat.messages)
- Redis (presence tracking, user cache)
- STOMP over SockJS (real-time WebSocket delivery)
- Optional E2E encryption (ECDH P-256, AES-256-GCM)
- Liquibase (PostgreSQL migrations)
- SpringDoc OpenAPI

## Development

```bash
# From the ops repo (starts Kafka, ScyllaDB, PostgreSQL, Redis)
task dev

# Or start infra only, then run from IDE
task dev:infra
./gradlew bootRun

# Run tests
./gradlew test
```

## Deployment

Deployed to k3s via Flux CD GitOps:

1. Push to master triggers Woodpecker CD pipeline
2. `./gradlew test` runs, then Kaniko builds the container image
3. Image pushed to Forgejo registry at `git.pmon.dev`
4. Woodpecker commits new image tag to the `schnappy/infra` repo
5. Flux detects the change and reconciles the HelmRelease

Production WebSocket endpoint at `wss://pmon.dev/api/ws/chat` in the `monitor` namespace.
