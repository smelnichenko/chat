# Chat Service

Real-time messaging service for pmon.dev with channel-based chat and optional E2E encryption.

## Architecture

Behind Istio ingress; Istio validates Keycloak JWTs (including for the WebSocket upgrade) and forwards to the sidecar. Uses Kafka as the message bus for persistence and delivery fan-out. Stores messages in ScyllaDB (day-bucketed partitions) and channel/member metadata in PostgreSQL. Delivers real-time messages over STOMP/SockJS WebSocket. Consumes Kafka `user.events` to sync users from the admin service.

```
Istio ingress --> sidecar --> Chat --> PostgreSQL (chat DB: channels, members, key bundles)
                                   --> ScyllaDB  (messages, day-bucketed)
                                   --> Kafka     (chat.messages, chat.events)
                                   --> Valkey    (presence, user cache)
              <-- WebSocket (STOMP/SockJS)
              <-- Kafka <-- Admin       (user event sync)
```

## Tech Stack

- Java 25, Spring Boot 4.0, Gradle 9.3
- PostgreSQL 17 — channels, members, user keys, key bundles
- ScyllaDB 6.2 — message persistence, CQL via DataStax driver
- Kafka 4.2 KRaft — message bus, partitioned `chat.messages`
- Valkey — presence tracking, user cache
- Spring WebSocket — STOMP over SockJS
- Optional E2E encryption — ECDH P-256, AES-256-GCM (keys handled client-side)
- Liquibase — PostgreSQL migrations
- SpringDoc OpenAPI

## Development

```bash
# From the ops repo (starts Kafka, ScyllaDB, PostgreSQL, Valkey)
task dev

# Or start infra only, then run from IDE
task dev:infra
./gradlew bootRun

# Run tests
./gradlew test
```

## Deployment

Deployed to kubeadm via Argo CD GitOps:

1. Push to master triggers Woodpecker CD
2. `./gradlew clean check` then Kaniko builds the image
3. Image pushed to Forgejo registry at `git.pmon.dev`
4. Woodpecker commits the new tag to `schnappy/infra`
5. Argo CD syncs the Application

Production WebSocket endpoint at `wss://pmon.dev/api/ws/chat` in the `schnappy-production-apps` namespace.
