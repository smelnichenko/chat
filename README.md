# Chat Service

Real-time messaging backend for pmon.dev — channel-based chat with optional client-side E2E encryption.

## Architecture

Behind the Istio ingress gateway. Istio terminates TLS, validates the Keycloak JWT, and forwards to the sidecar. Kafka is the message bus: `POST /messages` produces to `chat.messages` (persistence) and `events.chat.messages` (real-time fan-out). The persistence consumer in this service writes to ScyllaDB; Centrifugo (separate workload) consumes the events topic and pushes to subscribed clients over WebSocket. PostgreSQL holds channel/membership/key metadata, ScyllaDB holds message history (day-bucketed by `(channel_id, YYYY-MM-DD UTC)`), Valkey tracks presence.

```
Istio ingress --> sidecar --> Chat --> PostgreSQL  (channels, members, keys)
                                   --> ScyllaDB    (messages, day-bucketed)
                                   --> Kafka       (chat.messages → ScyllaDB persistence)
                                                   (events.chat.messages → Centrifugo fan-out)
                                   --> Valkey      (presence, user cache)
              <-- Kafka <-- Admin       (user.events sync)

(Real-time push to clients goes Kafka → Centrifugo → browser, not through chat-service.)
```

## Tech Stack

- Java 25, Spring Boot 4.0, Gradle 9.3
- PostgreSQL 17 — channels, members, user keys, channel key bundles
- ScyllaDB 6.2 — message history, CQL via DataStax driver 4.17
- Kafka — `chat.messages` (persistence), `events.chat.messages` (envelope topic for Centrifugo), `user.events` consumer
- Valkey — presence (sorted set, 60s TTL), local user cache
- Optional E2E encryption — ECDH P-256 + AES-256-GCM, key generation in the browser
- Liquibase — PostgreSQL migrations
- OpenTelemetry — traces to Tempo, metrics to Prometheus → Mimir
- ArchUnit — architectural test rules
- SpringDoc OpenAPI

## Real-time fan-out

This service no longer terminates WebSocket connections. The flow:

1. `POST /api/chat/channels/{id}/messages` arrives, server persists to ScyllaDB-via-Kafka, then publishes a publication envelope to `events.chat.messages` with header `x-centrifugo-channels: chat:room:<id>`.
2. Centrifugo's Kafka async-consumer reads the envelope, fans it out to every browser subscribed to `chat:room:<id>` over WebSocket.
3. Browsers obtain a per-channel subscription token from `admin` (`POST /api/realtime/sub-token`); admin checks membership against `chat`'s `GET /internal/membership` before signing.

Internal endpoint:

```
GET /internal/membership?user=<uuid>&channel=chat:room:<id>
  → 200 if the user is a channel member, 404 otherwise
```

mTLS-only path; mesh-level Istio AuthorizationPolicy DENY rejects every source SA except admin.

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

Production at `https://pmon.dev/api/chat/*` in the `schnappy-production` namespace. Real-time WebSocket endpoint at `wss://pmon.dev/realtime/connection/websocket` (Centrifugo).
