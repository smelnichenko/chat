# Chat Service

Real-time messaging with Kafka message bus, ScyllaDB persistence, and WebSocket delivery. Optional E2E encryption.

## Quick Start

```bash
cd ../ops && task dev        # Starts all infra (Kafka, ScyllaDB, Postgres) + services
cd ../ops && task dev:infra  # Infra only, run ChatApplication from IDE
```

## Key Classes

| File | Purpose |
|------|---------|
| `SecurityConfig.java` | Spring Security config — trusts gateway headers |
| `GatewayAuthFilter.java` | Reads JWT payload, populates SecurityContext |
| `PermissionInterceptor.java` | AOP aspect enforcing `@RequirePermission` (CHAT) |
| `ChatController.java` | Channel and message REST API |
| `ChatService.java` | Chat business logic |
| `ChatMessageConsumer.java` | Kafka consumer: persists to ScyllaDB + WebSocket fan-out |
| `UserEventConsumer.java` | Kafka consumer: syncs users from admin service |
| `ScyllaConfig.java` | CqlSession bean factory |
| `WebSocketConfig.java` | STOMP over SockJS configuration |

## API Endpoints

```
GET    /api/chat/channels                   # List user's channels
POST   /api/chat/channels                   # Create channel
POST   /api/chat/channels/{id}/join         # Join channel
POST   /api/chat/channels/{id}/leave        # Leave channel
GET    /api/chat/channels/{id}/messages     # Messages (paginated)
POST   /api/chat/channels/{id}/messages     # Send message
POST   /api/chat/channels/{id}/read         # Mark as read
# WebSocket: STOMP over SockJS at /api/ws/chat
```

## Data Stores

- **PostgreSQL** (monitor_chat): channels, channel_members, chat_users, user_keys, channel_key_bundles
- **ScyllaDB** (keyspace: chat): messages_by_channel, messages_by_user_v2, message_edits, reactions, attachments (day-bucketed partitions)
- **Kafka** topics: chat.messages (12p), chat.events (6p), chat.notifications (3p), user.events (3p)
- **Redis**: presence (sorted set with 60s TTL)

## Full Infrastructure Docs

See `schnappy/ops` repo `CLAUDE.md` for complete infrastructure documentation.
