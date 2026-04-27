# Chat Service

Real-time messaging backend for pmon.dev — channel-based chat with optional client-side E2E encryption.

## Architecture

Behind the Istio ingress gateway. Istio terminates TLS, validates the Keycloak JWT (including on the WebSocket upgrade), and forwards to the sidecar. Kafka is the message bus: `POST /messages` produces to `chat.messages` and returns; two consumer groups inside this service then persist to ScyllaDB and fan out to STOMP subscribers. PostgreSQL holds channel/membership/key metadata, ScyllaDB holds message history (day-bucketed by `(channel_id, YYYY-MM-DD UTC)`), and Valkey tracks presence.

```
Istio ingress --> sidecar --> Chat --> PostgreSQL (chat DB: channels, members, keys)
                                   --> ScyllaDB  (messages, day-bucketed)
                                   --> Kafka     (chat.messages — produce + self-consume)
                                   --> Valkey    (presence, user cache)
              <-- WebSocket (STOMP/SockJS, /topic/channel.{id})
              <-- Kafka <-- Admin       (user.events sync)
```

## Tech Stack

- Java 25, Spring Boot 4.0, Gradle 9.3
- PostgreSQL 17 — channels, members, user keys, channel key bundles
- ScyllaDB 6.2 — message history, CQL via DataStax driver 4.17
- Kafka — `chat.messages` (12p) message bus, `user.events` consumer
- Valkey — presence (sorted set, 60s TTL), local user cache
- Spring WebSocket — STOMP simple broker over SockJS
- Optional E2E encryption — ECDH P-256 + AES-256-GCM, key generation in the browser
- Liquibase — PostgreSQL migrations
- OpenTelemetry — traces to Tempo, metrics to Prometheus → Mimir
- ArchUnit — architectural test rules
- SpringDoc OpenAPI

## Real-time

```
WS handshake:  wss://pmon.dev/api/ws/chat
SUBSCRIBE:     /topic/channel.{channelId}     (SubscriptionGuard enforces membership)
SEND:          /app/chat.send                 (produces to chat.messages)
SEND:          /app/chat.read                 (read receipts)
```

## REST API

REST endpoints sit under `/api/chat` and require the `CHAT` permission. They cover channels, messages, members, edits, read receipts, hash-chain verification, and full E2E key management (per-user keys, per-channel key bundles, rotation). See [`CLAUDE.md`](CLAUDE.md) for the full list.

## Development

```bash
# From the ops repo (starts Kafka, ScyllaDB, PostgreSQL, Valkey)
task dev

# Or start infra only, then run from IDE
task dev:infra
./gradlew bootRun

# Tests
./gradlew test
```

## Deployment

Deployed to kubeadm via Argo CD GitOps:

1. Push to `main` triggers Woodpecker CD
2. `./gradlew clean check` then Kaniko builds the image
3. Image pushed to Forgejo registry at `git.pmon.dev`
4. Woodpecker commits the new tag to `schnappy/infra`
5. Argo CD syncs the Application

Production WebSocket endpoint at `wss://pmon.dev/api/ws/chat` in the `schnappy-production-apps` namespace.
