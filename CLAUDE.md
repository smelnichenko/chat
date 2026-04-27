# Chat Service

Real-time messaging backend for pmon.dev. Channel-based chat with optional client-side E2E encryption. Kafka is the message bus, ScyllaDB stores messages (day-bucketed), PostgreSQL stores channels/members/keys, STOMP over SockJS delivers to clients.

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
    ChatWebSocketController.java     STOMP: /app/chat.send, /app/chat.read
    GlobalExceptionHandler.java
  service/
    ChatService.java                 channel/message orchestration
    PresenceService.java             Valkey-backed online presence
    SystemChannelService.java        auto-provisioned channels (e.g. announcements)
    UserCacheService.java            local user cache (mirrored from admin via Kafka)
    UserProvisionerAdapter.java      lazy local-user upsert from JWT
  websocket/
    WebSocketConfig.java             simple in-memory broker on /topic, /queue
    WebSocketAuthInterceptor.java    JWT check on STOMP CONNECT
    SubscriptionGuard.java           enforces /topic/channel.{id} membership
  kafka/
    ChatKafkaProducer.java           writes to chat.messages
    ChatMessageConsumer.java         persists (chat-persistence) + fans out (chat-delivery)
    UserEventConsumer.java           consumes user.events
  repository/
    ScyllaMessageRepository.java     all CQL — messages_by_channel/_user_v2, edits, chain_heads
    {Channel,ChannelMember,ChatUser,UserKeys,ChannelKeyBundle}Repository.java   JPA
  entity/                            JPA entities
  dto/                               request/response DTOs
  config/
    SecurityConfig.java              trusts Keycloak JWT propagated by Istio
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
POST   /channels/{id}/messages            send (writes to Kafka, not directly to DB)
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

## STOMP (`/api/ws/chat`)

```
CONNECT          authenticated via WebSocketAuthInterceptor (Keycloak JWT)
SUBSCRIBE        /topic/channel.{channelId}     SubscriptionGuard checks membership
SEND             /app/chat.send                 produces to chat.messages
SEND             /app/chat.read                 read receipts
```

## Storage layout

- **PostgreSQL** — local DB `chat` (production) / `monitor_chat` (local dev): channels, channel_members, chat_users, user_keys, channel_key_bundles
- **ScyllaDB** keyspace `chat`: `messages_by_channel`, `messages_by_user_v2`, `message_edits`, `chain_heads` (hash-chain head per channel — used by `/verify`), `reactions_by_message`, `attachments_by_message`. All time-series tables partition by `(channel_id, bucket=YYYY-MM-DD UTC)`.
- **Valkey** — presence (sorted set with 60s TTL), local user cache
- **Kafka**:
  - `chat.messages` (12p) — produced and consumed by chat (two consumer groups: `chat-persistence` writes to ScyllaDB, `chat-delivery` fans out to STOMP)
  - `user.events` (3p) — consumer only, mirrors admin-service users
  - `chat.events` (6p) and `chat.notifications` (3p) are provisioned cluster-side but **not currently produced or consumed by this service** — placeholders for future consumers (notifications service, etc.). Don't wire them up here without a concrete consumer.

## Conventions specific to this service

- The HTTP `POST /channels/{id}/messages` does **not** write to ScyllaDB synchronously — it produces to `chat.messages` and returns. The `chat-persistence` consumer group is what makes the message durable. Tests that read back immediately after sending need to wait for the consumer.
- E2E encryption is **opt-in** per env (`monitor.chat.e2e-enabled`). The server stores ciphertext blobs and key bundles but never plaintext content when E2E is on; key generation/wrapping happens in the browser (Web Crypto API).
- `messages_by_user_v2` exists because v1 had the wrong partition key; v1 still in CQL schema but unused. Don't read from `messages_by_user`.
- `chain_heads` powers the optional hash-chain integrity feature — `/channels/{id}/verify` walks the chain back from the head.

## Full Infrastructure Docs

See `schnappy/ops` repo `CLAUDE.md`.
