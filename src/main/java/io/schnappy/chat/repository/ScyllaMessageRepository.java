package io.schnappy.chat.repository;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.uuid.Uuids;
import io.schnappy.chat.dto.ChatMessageDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Slf4j
@Repository
public class ScyllaMessageRepository {

    /** 100-ns ticks from 1582-10-15 (a time-based UUID's origin) to 1970-01-01. */
    private static final long UUID_EPOCH_TICKS = 0x01B2_1DD2_1381_4000L;
    private static final String COL_MESSAGE_ID = "message_id";
    private static final String COL_USER_UUID = "user_uuid";
    private static final String COL_USERNAME = "username";
    private static final String COL_CONTENT = "content";
    private static final String COL_PARENT_MESSAGE_ID = "parent_message_id";
    private static final String COL_EDITED = "edited";
    private static final String COL_DELETED = "deleted";
    private static final String COL_HASH = "hash";
    private static final String COL_PREV_HASH = "prev_hash";
    private static final String COL_KEY_VERSION = "key_version";
    private static final String COL_MESSAGE_TYPE = "message_type";
    private static final String COL_METADATA = "metadata";
    private static final String COL_EDIT_ID = "edit_id";

    private final CqlSession session;
    private final PreparedStatement insertByChannel;
    private final PreparedStatement insertByUser;
    private final PreparedStatement selectByChannel;
    private final PreparedStatement selectByChannelBefore;
    private final PreparedStatement getChainHead;
    private final PreparedStatement upsertChainHead;
    private final PreparedStatement insertEdit;
    private final PreparedStatement selectEdits;
    private final PreparedStatement selectLatestEdit;
    private final PreparedStatement markEdited;

    public ScyllaMessageRepository(CqlSession session) {
        this.session = session;

        this.insertByChannel = session.prepare(
            // no edited, no deleted: a retry would undo an edit made between its tries (an unwritten one reads false)
            "INSERT INTO messages_by_channel (channel_id, bucket, message_id, user_uuid, username, content, parent_message_id, hash, prev_hash, key_version, message_type, metadata) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        );

        this.insertByUser = session.prepare(
            "INSERT INTO messages_by_user_v2 (user_uuid, message_id, channel_id, bucket, content) " +
            "VALUES (?, ?, ?, ?, ?)"
        );

        this.selectByChannel = session.prepare(
            "SELECT message_id, user_uuid, username, content, parent_message_id, edited, deleted, hash, prev_hash, key_version, message_type, metadata " +
            "FROM messages_by_channel WHERE channel_id = ? AND bucket = ? LIMIT ?"
        );

        this.selectByChannelBefore = session.prepare(
            "SELECT message_id, user_uuid, username, content, parent_message_id, edited, deleted, hash, prev_hash, key_version, message_type, metadata " +
            "FROM messages_by_channel WHERE channel_id = ? AND bucket = ? AND message_id < ? LIMIT ?"
        );

        this.getChainHead = session.prepare(
            "SELECT hash, message_id FROM chain_heads WHERE channel_id = ?"
        );

        this.upsertChainHead = session.prepare(
            "INSERT INTO chain_heads (channel_id, hash, message_id) VALUES (?, ?, ?)"
        );

        this.insertEdit = session.prepare(
            "INSERT INTO message_edits (channel_id, bucket, message_id, edit_id, user_uuid, content, hash) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?)"
        );

        this.selectEdits = session.prepare(
            "SELECT edit_id, user_uuid, content, hash FROM message_edits " +
            "WHERE channel_id = ? AND bucket = ? AND message_id = ?"
        );

        this.selectLatestEdit = session.prepare(
            "SELECT content FROM message_edits " +
            "WHERE channel_id = ? AND bucket = ? AND message_id = ? LIMIT 1"
        );

        this.markEdited = session.prepare(
            "UPDATE messages_by_channel SET edited = true WHERE channel_id = ? AND bucket = ? AND message_id = ?"
        );
    }

    /**
     * The message into the history - the same row on every try, and the chain's order its rows' order (the order
     * verifyChain walks):
     * <ul>
     *   <li>a retry after its last write (the chain head names it already) writes nothing - chained again, it would be
     *       chained to itself;</li>
     *   <li>a retry after a write that half landed (the row, not the head) writes that row again, the same - the head has
     *       not moved, so its id ({@link #rowId}, made after the head), prev_hash and hash are what they were: an upsert
     *       of itself;</li>
     *   <li>a message delivered again once others followed it (a replay, offsets lost) is appended under a new id after
     *       the head - a second row, the chain whole - never written over its first row, which later rows chain to.</li>
     * </ul>
     * All three rest on one writer per channel at a time - the read of the head and its write are no transaction:
     * chat.messages is keyed by channel, its partitions fixed (12), each one consumer's (Kafka's group), ScyllaDB one
     * replica. More partitions, a second writer, or replicas read below quorum would fork the chain (a lightweight
     * transaction on chain_heads then).
     */
    public UUID saveMessage(ChatMessageDto msg, UUID parentMessageId) {
        long channelId = msg.getChannelId();
        var head = session.execute(getChainHead.bind(channelId)).one();
        UUID headId = head != null ? head.getUuid(COL_MESSAGE_ID) : null;
        long lsb = rowLsb(msg);
        if (headId != null && headId.getLeastSignificantBits() == lsb) {
            return headId;
        }
        UUID messageId = rowId(msg, headId, lsb);
        // the row id's own day: an edit finds the row by its id's time (getMessages, ChatService.editMessage)
        String bucket = bucketForDate(Instant.ofEpochMilli(Uuids.unixTimestamp(messageId)));
        String prevHash = head != null ? head.getString(COL_HASH) : "0";
        String hash = computeMessageHash(prevHash, channelId, msg.getUserUuid(), messageId, msg.getContent());

        session.execute(insertByChannel.bind(
            channelId, bucket, messageId, msg.getUserUuid(), msg.getUsername(), msg.getContent(),
            // parent_message_id is text: a UUID bound to it has no codec (TEXT <-> UUID) and no reply was ever stored
            parentMessageId == null ? null : parentMessageId.toString(), hash, prevHash, msg.getKeyVersion(),
            msg.getMessageType(), msg.getMetadata()
        ));

        session.execute(insertByUser.bind(
            msg.getUserUuid(), messageId, channelId, bucket, msg.getContent()
        ));

        session.execute(upsertChainHead.bind(channelId, hash, messageId));

        return messageId;
    }

    /**
     * A message's row id, time-based: its time the producer's (createdAt), or one tick after the chain head's when that
     * is later - a channel's ids rise in the order its messages are chained, whatever the producers' clocks say (two
     * producers, a clock step, rows of the old write-time ids); its other bits {@link #rowLsb}. The head unchanged
     * between tries, it is the same on every try.
     */
    static UUID rowId(ChatMessageDto msg, UUID head, long lsb) {
        long millis = msg.getCreatedAt() != null ? msg.getCreatedAt().toEpochMilli() : System.currentTimeMillis();
        long ticks = millis * 10_000 + UUID_EPOCH_TICKS;
        if (head != null && head.version() == 1) {
            ticks = Math.max(ticks, head.timestamp() + 1);
        }
        return new UUID(msbOf(ticks), lsb);
    }

    /** A version-1 UUID's high bits for a time in 100-ns ticks since 1582-10-15: time_low, time_mid, version, time_hi. */
    static long msbOf(long ticks) {
        return ((ticks & 0xFFFF_FFFFL) << 32) | (((ticks >>> 32) & 0xFFFFL) << 16) | 0x1000L | ((ticks >>> 48) & 0x0FFFL);
    }

    /**
     * A row id's other bits: 62 of a SHA-256 of the producer's message id, under the IETF variant - the same on every
     * try, and as unique as the whole id (a time-based producer id's own low bits are its JVM's, shared by all it makes).
     * No producer id: random, as a fresh time-based id was.
     */
    static long rowLsb(ChatMessageDto msg) {
        String id = msg.getMessageId();
        long bits = id != null && !id.isBlank()
                ? ByteBuffer.wrap(sha256Bytes(id)).getLong()
                : UUID.randomUUID().getLeastSignificantBits();
        return (bits & 0x3FFF_FFFF_FFFF_FFFFL) | 0x8000_0000_0000_0000L;
    }

    public String getChannelChainHead(long channelId) {
        var row = session.execute(getChainHead.bind(channelId)).one();
        return row != null ? row.getString(COL_HASH) : "0";
    }

    public void saveEdit(long channelId, String bucket, UUID messageId, UUID userUuid, String content, String originalHash) {
        UUID editId = Uuids.timeBased();
        String editHash = computeEditHash(originalHash, editId, userUuid, content);

        session.execute(insertEdit.bind(channelId, bucket, messageId, editId, userUuid, content, editHash));

        session.execute(markEdited.bind(channelId, bucket, messageId));
    }

    public String getLatestEditContent(long channelId, String bucket, UUID messageId) {
        var row = session.execute(selectLatestEdit.bind(channelId, bucket, messageId)).one();
        return row != null ? row.getString(COL_CONTENT) : null;
    }

    public List<EditRecord> getEdits(long channelId, String bucket, UUID messageId) {
        var rs = session.execute(selectEdits.bind(channelId, bucket, messageId));
        List<EditRecord> edits = new ArrayList<>();
        for (Row row : rs) {
            edits.add(new EditRecord(
                row.getUuid(COL_EDIT_ID).toString(),
                row.getUuid(COL_USER_UUID),
                row.getString(COL_CONTENT),
                row.getString(COL_HASH),
                Instant.ofEpochMilli(Uuids.unixTimestamp(row.getUuid(COL_EDIT_ID)))
            ));
        }
        return edits;
    }

    public record EditRecord(String editId, UUID userUuid, String content, String hash, Instant createdAt) {}

    public List<ChatMessageDto> getMessages(long channelId, String bucket, UUID beforeMessageId, int limit) {
        var rs = session.execute(beforeMessageId != null
            ? selectByChannelBefore.bind(channelId, bucket, beforeMessageId, limit)
            : selectByChannel.bind(channelId, bucket, limit));

        List<ChatMessageDto> messages = new ArrayList<>();
        for (Row row : rs) {
            if (row.getBoolean(COL_DELETED)) continue;
            UUID msgId = row.getUuid(COL_MESSAGE_ID);
            String parentId = row.getString(COL_PARENT_MESSAGE_ID);
            boolean edited = row.getBoolean(COL_EDITED);

            String editedContent = null;
            if (edited) {
                editedContent = getLatestEditContent(channelId, bucket, msgId);
            }

            messages.add(ChatMessageDto.builder()
                .messageId(msgId.toString())
                .channelId(channelId)
                .userUuid(row.getUuid(COL_USER_UUID))
                .username(row.getString(COL_USERNAME))
                .content(row.getString(COL_CONTENT))
                .parentMessageId(parentId)
                .createdAt(Instant.ofEpochMilli(Uuids.unixTimestamp(msgId)))
                .hash(row.getString(COL_HASH))
                .prevHash(row.getString(COL_PREV_HASH))
                .editedContent(editedContent)
                .keyVersion(row.isNull(COL_KEY_VERSION) ? null : row.getInt(COL_KEY_VERSION))
                .messageType(row.getString(COL_MESSAGE_TYPE))
                .metadata(row.getString(COL_METADATA))
                .build());
        }
        return messages;
    }

    public List<ChatMessageDto> getRecentMessages(long channelId, int limit) {
        String today = LocalDate.now(ZoneOffset.UTC).toString();
        List<ChatMessageDto> messages = new ArrayList<>(getMessages(channelId, today, null, limit));
        if (messages.size() < limit) {
            String yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1).toString();
            messages.addAll(getMessages(channelId, yesterday, null, limit - messages.size()));
        }
        Collections.reverse(messages);
        return messages;
    }

    public ChainVerification verifyChain(long channelId, Instant channelCreatedAt) {
        LocalDate start = LocalDate.ofInstant(channelCreatedAt, ZoneOffset.UTC);
        LocalDate end = LocalDate.now(ZoneOffset.UTC);

        ChainState state = new ChainState();

        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            String bucket = date.toString();
            var rs = session.execute(
                "SELECT message_id, user_uuid, content, hash, prev_hash, deleted " +
                "FROM messages_by_channel WHERE channel_id = ? AND bucket = ? ORDER BY message_id ASC",
                channelId, bucket);

            for (Row row : rs) {
                if (!row.getBoolean(COL_DELETED)) {
                    verifyMessageInChain(row, channelId, state);
                }
            }
        }

        return new ChainVerification(state.messageCount, state.validCount,
                state.messageCount == state.validCount, state.firstBrokenMessageId);
    }

    private static class ChainState {
        String expectedPrevHash = "0";
        int messageCount = 0;
        int validCount = 0;
        String firstBrokenMessageId = null;
    }

    private void verifyMessageInChain(Row row, long channelId, ChainState state) {
        state.messageCount++;

        UUID msgId = row.getUuid(COL_MESSAGE_ID);
        String storedHash = row.getString(COL_HASH);
        String storedPrevHash = row.getString(COL_PREV_HASH);

        if (storedHash == null) {
            state.expectedPrevHash = "0";
            state.validCount++;
            return;
        }

        if (!state.expectedPrevHash.equals(storedPrevHash)) {
            if (state.firstBrokenMessageId == null) {
                state.firstBrokenMessageId = msgId.toString();
            }
            state.expectedPrevHash = storedHash;
            return;
        }

        String recomputed = computeMessageHash(
            storedPrevHash, channelId, row.getUuid(COL_USER_UUID), msgId, row.getString(COL_CONTENT));
        if (recomputed.equals(storedHash)) {
            state.validCount++;
        } else if (state.firstBrokenMessageId == null) {
            state.firstBrokenMessageId = msgId.toString();
        }

        state.expectedPrevHash = storedHash;
    }

    public record ChainVerification(int messageCount, int validCount, boolean intact, String firstBrokenMessageId) {}

    public void deleteMessagesByChannel(long channelId, Instant channelCreatedAt) {
        LocalDate start = LocalDate.ofInstant(channelCreatedAt, ZoneOffset.UTC);
        LocalDate end = LocalDate.now(ZoneOffset.UTC);
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            String bucket = date.toString();
            session.execute("DELETE FROM messages_by_channel WHERE channel_id = ? AND bucket = ?",
                channelId, bucket);
            session.execute("DELETE FROM message_edits WHERE channel_id = ? AND bucket = ?",
                channelId, bucket);
        }
        session.execute("DELETE FROM chain_heads WHERE channel_id = ?", channelId);
    }

    public static String bucketForDate(Instant instant) {
        return LocalDate.ofInstant(instant, ZoneOffset.UTC).toString();
    }

    static String computeMessageHash(String prevHash, long channelId, UUID userUuid, UUID messageId, String content) {
        String input = prevHash + "|" + channelId + "|" + userUuid + "|" + messageId + "|" + content;
        return sha256(input);
    }

    static String computeEditHash(String originalHash, UUID editId, UUID userUuid, String content) {
        String input = originalHash + "|" + editId + "|" + userUuid + "|" + content;
        return sha256(input);
    }

    private static String sha256(String input) {
        return HexFormat.of().formatHex(sha256Bytes(input));
    }

    private static byte[] sha256Bytes(String input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
