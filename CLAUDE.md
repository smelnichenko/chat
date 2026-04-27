# Chat Service

Real-time messaging backend for pmon.dev. Channel-based chat with optional client-side E2E encryption. Kafka is the message bus, ScyllaDB stores messages (day-bucketed), PostgreSQL stores channel/membership/key metadata, Centrifugo (separate workload) fans out to subscribed clients over WebSocket.

## Quick Start

```bash
cd ../ops && task dev        # all infra (Kafka, ScyllaDB, Postgres, Valkey) + services
cd ../ops && task dev:infra  # infra only, run ChatApplication from IDE
./gradlew test               # JUnit + Spring Boot test + ArchUnit
```

## Layout

```
src/main/java/io/schnappy/chat/
  ChatApplication.java
  controller/
    ChatController.java              REST: /chat/**
    InternalController.java          /internal/membership for admin's sub-token mint
    GlobalExceptionHandler.java
  service/
    ChatService.java                 channel/message orchestration
    PresenceService.java             Valkey-backed online presence
    SystemChannelService.java        auto-provisioned channels (e.g. announcements)
    UserCacheService.java            local user cache (mirrored from admin via Kafka)
    UserProvisionerAdapter.java      lazy local-user upsert from JWT
  kafka/
    ChatKafkaProducer.java           writes to chat.messages (persistence pipeline)
    ChatMessageConsumer.java         persists chat.messages → ScyllaDB
    EventEnvelope.java               common envelope record
    EventEnvelopeProducer.java       writes to events.chat.messages (Centrifugo fan-out)
    UserEventConsumer.java           consumes user.events
  repository/
    ScyllaMessageRepository.java     all CQL — messages_by_channel/_user_v2, edits, chain_heads
    {Channel,ChannelMember,ChatUser,UserKeys,ChannelKeyBundle}Repository.java   JPA
  entity/                            JPA entities
  dto/                               request/response DTOs
  config/
    SecurityConfig.java              trusts Keycloak JWT propagated by Istio; permits /internal/**
    KafkaConfig.java
    ScyllaConfig.java                CqlSession factory
    ChatProperties.java              `monitor.chat.*` config (e2e-enabled, scylla.*)
  security/                          GatewayAuthFilter, PermissionInterceptor (CHAT), RequirePermission
```

## REST endpoints (auth: Keycloak JWT, permission: `CHAT`)

All under `/api/chat`:

```
# Channels & messages
GET    /channels                          list user's channels
POST   /channels                          create channel
DELETE /channels/{id}                     delete (owner)
POST   /channels/{id}/leave
POST   /channels/{id}/kick                remove member (owner/mod)
POST   /channels/{id}/invite              invite user
GET    /channels/{id}/members
GET    /channels/{id}/messages            paginated, day-bucketed read
POST   /channels/{id}/messages            send (publishes to Kafka, returns immediately)
PUT    /channels/{id}/messages/{msgId}    edit
GET    /channels/{id}/messages/{msgId}/edits   edit history
POST   /channels/{id}/read                mark as read
GET    /channels/{id}/verify              hash-chain integrity check

# Users
GET    /users                             cached user list

# E2E key management
GET    /keys                              caller's published keys
POST   /keys                              upload initial keys
PUT    /keys                              rotate caller's keys
GET    /keys/public                       fetch peer public keys
GET    /channels/{id}/keys                channel key bundle
POST   /channels/{id}/keys                set channel keys
POST   /channels/{id}/keys/rotate         rotate channel key (re-wrap for members)
```

## Internal endpoint (admin-only, mTLS-fronted)

```
GET /internal/membership?user=<uuid>&channel=chat:room:<id>
  → 200 if user is a member, 404 otherwise
```

`/internal/**` is `permitAll` in Spring Security; mesh-level Istio
AuthorizationPolicy DENY rejects every source SA except the admin
service. The admin service calls this when minting Centrifugo
subscription tokens.

## Real-time fan-out (Centrifugo)

This service does NOT terminate WebSocket connections. The flow:

1. `POST /messages` → `ChatService` writes to `chat.messages` (durable) AND publishes a publication envelope to `events.chat.messages` with header `x-centrifugo-channels: chat:room:<id>`. Synchronous return; persistence to ScyllaDB happens in the `chat-persistence` consumer.
2. Centrifugo's Kafka async-consumer (different workload) reads the envelope, fans it out to every browser subscribed to `chat:room:<id>` over WebSocket.
3. Browsers obtain a per-channel subscription token from `admin` (`POST /api/realtime/sub-token`); admin checks membership via the `/internal/membership` endpoint above before signing.

## Storage layout

- **PostgreSQL** — local DB `chat` (production) / `monitor_chat` (local dev): channels, channel_members, chat_users, user_keys, channel_key_bundles
- **ScyllaDB** keyspace `chat`: `messages_by_channel`, `messages_by_user_v2`, `message_edits`, `chain_heads` (hash-chain head per channel — used by `/verify`), `reactions_by_message`, `attachments_by_message`. All time-series tables partition by `(channel_id, bucket=YYYY-MM-DD UTC)`.
- **Valkey** — presence (sorted set with 60s TTL), local user cache
- **Kafka**:
  - `chat.messages` (12p) — produced by `ChatKafkaProducer`, consumed by `chat-persistence` (writes to ScyllaDB). Single consumer group now; the `chat-delivery` group that used to STOMP-fan-out is gone (Centrifugo does that).
  - `events.chat.messages` (12p) — produced by `EventEnvelopeProducer`, consumed by Centrifugo and by the ClickHouse Kafka engine (analytics).
  - `user.events` (3p) — consumer only, mirrors admin-service users.

## Conventions specific to this service

- `POST /channels/{id}/messages` returns 200 once Kafka accepts the produce, NOT once ScyllaDB has the row. Tests that read back immediately must wait for `chat-persistence`.
- E2E encryption is **opt-in** per env (`monitor.chat.e2e-enabled`). The server stores ciphertext + wrapped keys but never plaintext when E2E is on. Key generation/wrapping happens in the browser via Web Crypto.
- `messages_by_user_v2` exists because v1 had the wrong partition key; v1 still in CQL schema but unused. Don't read from `messages_by_user`.
- `chain_heads` powers the optional hash-chain integrity feature — `/channels/{id}/verify` walks the chain back from the head.

## Full Infrastructure Docs

See `schnappy/ops` repo `CLAUDE.md`.
