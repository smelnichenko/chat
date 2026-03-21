package io.schnappy.chat.repository;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.BoundStatement;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.uuid.Uuids;
import io.schnappy.chat.dto.ChatMessageDto;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScyllaMessageRepositoryTest {

    // --- Static method tests (no CqlSession needed) ---

    @Test
    void bucketForDate_returnsUtcDate() {
        Instant instant = Instant.parse("2026-03-20T15:30:00Z");
        assertThat(ScyllaMessageRepository.bucketForDate(instant)).isEqualTo("2026-03-20");
    }

    @Test
    void bucketForDate_nearMidnightUtc_usesCorrectDate() {
        assertThat(ScyllaMessageRepository.bucketForDate(Instant.parse("2026-03-20T23:59:59Z")))
                .isEqualTo("2026-03-20");
        assertThat(ScyllaMessageRepository.bucketForDate(Instant.parse("2026-03-21T00:00:01Z")))
                .isEqualTo("2026-03-21");
    }

    @Test
    void bucketForDate_epoch_returnsEpochDate() {
        assertThat(ScyllaMessageRepository.bucketForDate(Instant.EPOCH)).isEqualTo("1970-01-01");
    }

    @Test
    void computeMessageHash_deterministicForSameInputs() {
        UUID messageId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        String hash1 = ScyllaMessageRepository.computeMessageHash("prevhash", 1L, 10L, messageId, "Hello");
        String hash2 = ScyllaMessageRepository.computeMessageHash("prevhash", 1L, 10L, messageId, "Hello");
        assertThat(hash1).isEqualTo(hash2);
    }

    @Test
    void computeMessageHash_differentContent_producesDifferentHash() {
        UUID messageId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        String hash1 = ScyllaMessageRepository.computeMessageHash("0", 1L, 10L, messageId, "Hello");
        String hash2 = ScyllaMessageRepository.computeMessageHash("0", 1L, 10L, messageId, "World");
        assertThat(hash1).isNotEqualTo(hash2);
    }

    @Test
    void computeMessageHash_differentPrevHash_producesDifferentHash() {
        UUID messageId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        String hash1 = ScyllaMessageRepository.computeMessageHash("abc", 1L, 10L, messageId, "Hello");
        String hash2 = ScyllaMessageRepository.computeMessageHash("def", 1L, 10L, messageId, "Hello");
        assertThat(hash1).isNotEqualTo(hash2);
    }

    @Test
    void computeMessageHash_differentChannelId_producesDifferentHash() {
        UUID messageId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        String hash1 = ScyllaMessageRepository.computeMessageHash("0", 1L, 10L, messageId, "Hello");
        String hash2 = ScyllaMessageRepository.computeMessageHash("0", 2L, 10L, messageId, "Hello");
        assertThat(hash1).isNotEqualTo(hash2);
    }

    @Test
    void computeMessageHash_returnsHexString() {
        UUID messageId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        String hash = ScyllaMessageRepository.computeMessageHash("0", 1L, 10L, messageId, "Hello");
        assertThat(hash).hasSize(64).matches("[0-9a-f]+");
    }

    @Test
    void computeEditHash_deterministicForSameInputs() {
        UUID editId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        String hash1 = ScyllaMessageRepository.computeEditHash("origHash", editId, 10L, "edited content");
        String hash2 = ScyllaMessageRepository.computeEditHash("origHash", editId, 10L, "edited content");
        assertThat(hash1).isEqualTo(hash2);
    }

    @Test
    void computeEditHash_differentContent_producesDifferentHash() {
        UUID editId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        String hash1 = ScyllaMessageRepository.computeEditHash("origHash", editId, 10L, "v1");
        String hash2 = ScyllaMessageRepository.computeEditHash("origHash", editId, 10L, "v2");
        assertThat(hash1).isNotEqualTo(hash2);
    }

    @Test
    void computeEditHash_differentOriginalHash_producesDifferentHash() {
        UUID editId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        String hash1 = ScyllaMessageRepository.computeEditHash("hash1", editId, 10L, "content");
        String hash2 = ScyllaMessageRepository.computeEditHash("hash2", editId, 10L, "content");
        assertThat(hash1).isNotEqualTo(hash2);
    }

    @Test
    void computeEditHash_returnsHexString() {
        UUID editId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        String hash = ScyllaMessageRepository.computeEditHash("origHash", editId, 10L, "content");
        assertThat(hash).hasSize(64).matches("[0-9a-f]+");
    }

    @Test
    void chainVerification_recordAccessors() {
        var cv = new ScyllaMessageRepository.ChainVerification(10, 8, false, "msg-5");
        assertThat(cv.messageCount()).isEqualTo(10);
        assertThat(cv.validCount()).isEqualTo(8);
        assertThat(cv.intact()).isFalse();
        assertThat(cv.firstBrokenMessageId()).isEqualTo("msg-5");
    }

    @Test
    void editRecord_recordAccessors() {
        Instant now = Instant.now();
        var er = new ScyllaMessageRepository.EditRecord("edit-1", 10L, "content", "hash123", now);
        assertThat(er.editId()).isEqualTo("edit-1");
        assertThat(er.userId()).isEqualTo(10L);
        assertThat(er.content()).isEqualTo("content");
        assertThat(er.hash()).isEqualTo("hash123");
        assertThat(er.createdAt()).isEqualTo(now);
    }

    // --- Instance method tests (mock CqlSession) ---

    @Nested
    class InstanceMethodTests {

        @Test
        void saveMessage_executesInserts_andReturnsTimeBasedUuid() {
            var session = mock(CqlSession.class);
            var preparedStmt = mock(PreparedStatement.class);
            var boundStmt = mock(BoundStatement.class);
            when(session.prepare(anyString())).thenReturn(preparedStmt);

            var chainHeadRs = mock(ResultSet.class);
            when(chainHeadRs.one()).thenReturn(null); // no existing chain head
            when(preparedStmt.bind(any(Object[].class))).thenReturn(boundStmt);
            when(session.execute(any(BoundStatement.class))).thenReturn(chainHeadRs);

            var repo = new ScyllaMessageRepository(session);
            var msg = ChatMessageDto.builder()
                    .channelId(1L).userId(10L).username("alice").content("Hello").build();

            UUID messageId = repo.saveMessage(msg, null);

            assertThat(messageId).isNotNull();
        }

        @Test
        void getChannelChainHead_noRow_returnsZero() {
            var session = mock(CqlSession.class);
            var preparedStmt = mock(PreparedStatement.class);
            var boundStmt = mock(BoundStatement.class);
            when(session.prepare(anyString())).thenReturn(preparedStmt);
            when(preparedStmt.bind(any(Object[].class))).thenReturn(boundStmt);

            var rs = mock(ResultSet.class);
            when(rs.one()).thenReturn(null);
            when(session.execute(any(BoundStatement.class))).thenReturn(rs);

            var repo = new ScyllaMessageRepository(session);
            assertThat(repo.getChannelChainHead(1L)).isEqualTo("0");
        }

        @Test
        void getChannelChainHead_hasRow_returnsHashValue() {
            var session = mock(CqlSession.class);
            var preparedStmt = mock(PreparedStatement.class);
            var boundStmt = mock(BoundStatement.class);
            when(session.prepare(anyString())).thenReturn(preparedStmt);
            when(preparedStmt.bind(any(Object[].class))).thenReturn(boundStmt);

            var row = mock(Row.class);
            when(row.getString("hash")).thenReturn("abc123");
            var rs = mock(ResultSet.class);
            when(rs.one()).thenReturn(row);
            when(session.execute(any(BoundStatement.class))).thenReturn(rs);

            var repo = new ScyllaMessageRepository(session);
            assertThat(repo.getChannelChainHead(1L)).isEqualTo("abc123");
        }

        @Test
        void saveEdit_executesTwoStatements() {
            var session = mock(CqlSession.class);
            var preparedStmt = mock(PreparedStatement.class);
            var boundStmt = mock(BoundStatement.class);
            when(session.prepare(anyString())).thenReturn(preparedStmt);
            when(preparedStmt.bind(any(Object[].class))).thenReturn(boundStmt);
            when(session.execute(any(BoundStatement.class))).thenReturn(mock(ResultSet.class));

            var repo = new ScyllaMessageRepository(session);
            repo.saveEdit(1L, "2026-03-20", UUID.randomUUID(), 10L, "edited", "origHash");

            // insertEdit + markEdited = 2 bound statement executions
            verify(session, org.mockito.Mockito.atLeast(2)).execute(any(BoundStatement.class));
        }

        @Test
        void getLatestEditContent_noRow_returnsNull() {
            var session = mock(CqlSession.class);
            var preparedStmt = mock(PreparedStatement.class);
            var boundStmt = mock(BoundStatement.class);
            when(session.prepare(anyString())).thenReturn(preparedStmt);
            when(preparedStmt.bind(any(Object[].class))).thenReturn(boundStmt);

            var rs = mock(ResultSet.class);
            when(rs.one()).thenReturn(null);
            when(session.execute(any(BoundStatement.class))).thenReturn(rs);

            var repo = new ScyllaMessageRepository(session);
            assertThat(repo.getLatestEditContent(1L, "2026-03-20", UUID.randomUUID())).isNull();
        }

        @Test
        void getLatestEditContent_hasRow_returnsContent() {
            var session = mock(CqlSession.class);
            var preparedStmt = mock(PreparedStatement.class);
            var boundStmt = mock(BoundStatement.class);
            when(session.prepare(anyString())).thenReturn(preparedStmt);
            when(preparedStmt.bind(any(Object[].class))).thenReturn(boundStmt);

            var row = mock(Row.class);
            when(row.getString("content")).thenReturn("edited content");
            var rs = mock(ResultSet.class);
            when(rs.one()).thenReturn(row);
            when(session.execute(any(BoundStatement.class))).thenReturn(rs);

            var repo = new ScyllaMessageRepository(session);
            assertThat(repo.getLatestEditContent(1L, "2026-03-20", UUID.randomUUID()))
                    .isEqualTo("edited content");
        }

        @Test
        void getEdits_emptyResultSet_returnsEmptyList() {
            var session = mock(CqlSession.class);
            var preparedStmt = mock(PreparedStatement.class);
            var boundStmt = mock(BoundStatement.class);
            when(session.prepare(anyString())).thenReturn(preparedStmt);
            when(preparedStmt.bind(any(Object[].class))).thenReturn(boundStmt);

            var rs = mock(ResultSet.class);
            when(rs.iterator()).thenReturn(Collections.emptyIterator());
            when(session.execute(any(BoundStatement.class))).thenReturn(rs);

            var repo = new ScyllaMessageRepository(session);
            assertThat(repo.getEdits(1L, "2026-03-20", UUID.randomUUID())).isEmpty();
        }

        @Test
        void getEdits_withRows_returnsEditRecords() {
            var session = mock(CqlSession.class);
            var preparedStmt = mock(PreparedStatement.class);
            var boundStmt = mock(BoundStatement.class);
            when(session.prepare(anyString())).thenReturn(preparedStmt);
            when(preparedStmt.bind(any(Object[].class))).thenReturn(boundStmt);

            UUID editId = Uuids.timeBased();
            var row = mock(Row.class);
            when(row.getUuid("edit_id")).thenReturn(editId);
            when(row.getLong("user_id")).thenReturn(10L);
            when(row.getString("content")).thenReturn("edited text");
            when(row.getString("hash")).thenReturn("edithash123");

            var rs = mock(ResultSet.class);
            when(rs.iterator()).thenReturn(List.of(row).iterator());
            when(session.execute(any(BoundStatement.class))).thenReturn(rs);

            var repo = new ScyllaMessageRepository(session);
            var edits = repo.getEdits(1L, "2026-03-20", UUID.randomUUID());

            assertThat(edits).hasSize(1);
            assertThat(edits.get(0).userId()).isEqualTo(10L);
            assertThat(edits.get(0).content()).isEqualTo("edited text");
            assertThat(edits.get(0).hash()).isEqualTo("edithash123");
        }

        @Test
        void getMessages_withoutBeforeId_returnsEmptyForEmptyResult() {
            var session = mock(CqlSession.class);
            var preparedStmt = mock(PreparedStatement.class);
            var boundStmt = mock(BoundStatement.class);
            when(session.prepare(anyString())).thenReturn(preparedStmt);
            when(preparedStmt.bind(any(Object[].class))).thenReturn(boundStmt);

            var rs = mock(ResultSet.class);
            when(rs.iterator()).thenReturn(Collections.emptyIterator());
            when(session.execute(any(BoundStatement.class))).thenReturn(rs);

            var repo = new ScyllaMessageRepository(session);
            assertThat(repo.getMessages(1L, "2026-03-20", null, 50)).isEmpty();
        }

        @Test
        void getMessages_withBeforeId_returnsEmptyForEmptyResult() {
            var session = mock(CqlSession.class);
            var preparedStmt = mock(PreparedStatement.class);
            var boundStmt = mock(BoundStatement.class);
            when(session.prepare(anyString())).thenReturn(preparedStmt);
            when(preparedStmt.bind(any(Object[].class))).thenReturn(boundStmt);

            var rs = mock(ResultSet.class);
            when(rs.iterator()).thenReturn(Collections.emptyIterator());
            when(session.execute(any(BoundStatement.class))).thenReturn(rs);

            var repo = new ScyllaMessageRepository(session);
            assertThat(repo.getMessages(1L, "2026-03-20", Uuids.timeBased(), 50)).isEmpty();
        }

        @Test
        void getMessages_deletedMessage_isSkipped() {
            var session = mock(CqlSession.class);
            var preparedStmt = mock(PreparedStatement.class);
            var boundStmt = mock(BoundStatement.class);
            when(session.prepare(anyString())).thenReturn(preparedStmt);
            when(preparedStmt.bind(any(Object[].class))).thenReturn(boundStmt);

            var row = mock(Row.class);
            when(row.getBoolean("deleted")).thenReturn(true);

            var rs = mock(ResultSet.class);
            when(rs.iterator()).thenReturn(List.of(row).iterator());
            when(session.execute(any(BoundStatement.class))).thenReturn(rs);

            var repo = new ScyllaMessageRepository(session);
            assertThat(repo.getMessages(1L, "2026-03-20", null, 50)).isEmpty();
        }

        @Test
        void getMessages_normalMessage_returnsMapped() {
            var session = mock(CqlSession.class);
            var preparedStmt = mock(PreparedStatement.class);
            var boundStmt = mock(BoundStatement.class);
            when(session.prepare(anyString())).thenReturn(preparedStmt);
            when(preparedStmt.bind(any(Object[].class))).thenReturn(boundStmt);

            UUID msgId = Uuids.timeBased();
            var row = mock(Row.class);
            when(row.getBoolean("deleted")).thenReturn(false);
            when(row.getUuid("message_id")).thenReturn(msgId);
            when(row.getLong("user_id")).thenReturn(10L);
            when(row.getString("username")).thenReturn("alice");
            when(row.getString("content")).thenReturn("Hello");
            when(row.getUuid("parent_message_id")).thenReturn(null);
            when(row.getBoolean("edited")).thenReturn(false);
            when(row.getString("hash")).thenReturn("hash123");
            when(row.getString("prev_hash")).thenReturn("0");
            when(row.isNull("key_version")).thenReturn(true);
            when(row.getString("message_type")).thenReturn(null);
            when(row.getString("metadata")).thenReturn(null);

            var rs = mock(ResultSet.class);
            when(rs.iterator()).thenReturn(List.of(row).iterator());
            when(session.execute(any(BoundStatement.class))).thenReturn(rs);

            var repo = new ScyllaMessageRepository(session);
            var messages = repo.getMessages(1L, "2026-03-20", null, 50);

            assertThat(messages).hasSize(1);
            assertThat(messages.get(0).getMessageId()).isEqualTo(msgId.toString());
            assertThat(messages.get(0).getUserId()).isEqualTo(10L);
            assertThat(messages.get(0).getContent()).isEqualTo("Hello");
            assertThat(messages.get(0).getEditedContent()).isNull();
        }

        @Test
        void getMessages_editedMessage_fetchesLatestEditContent() {
            var session = mock(CqlSession.class);
            var preparedStmt = mock(PreparedStatement.class);
            var boundStmt = mock(BoundStatement.class);
            when(session.prepare(anyString())).thenReturn(preparedStmt);
            when(preparedStmt.bind(any(Object[].class))).thenReturn(boundStmt);

            UUID msgId = Uuids.timeBased();
            var row = mock(Row.class);
            when(row.getBoolean("deleted")).thenReturn(false);
            when(row.getUuid("message_id")).thenReturn(msgId);
            when(row.getLong("user_id")).thenReturn(10L);
            when(row.getString("username")).thenReturn("alice");
            when(row.getString("content")).thenReturn("Original");
            when(row.getUuid("parent_message_id")).thenReturn(null);
            when(row.getBoolean("edited")).thenReturn(true);
            when(row.getString("hash")).thenReturn("hash123");
            when(row.getString("prev_hash")).thenReturn("0");
            when(row.isNull("key_version")).thenReturn(false);
            when(row.getInt("key_version")).thenReturn(1);
            when(row.getString("message_type")).thenReturn("text");
            when(row.getString("metadata")).thenReturn("{}");

            var msgRs = mock(ResultSet.class);
            when(msgRs.iterator()).thenReturn(List.of(row).iterator());

            var editRow = mock(Row.class);
            when(editRow.getString("content")).thenReturn("Edited content");
            var editRs = mock(ResultSet.class);
            when(editRs.one()).thenReturn(editRow);

            when(session.execute(any(BoundStatement.class))).thenReturn(msgRs, editRs);

            var repo = new ScyllaMessageRepository(session);
            var messages = repo.getMessages(1L, "2026-03-20", null, 50);

            assertThat(messages).hasSize(1);
            assertThat(messages.get(0).getEditedContent()).isEqualTo("Edited content");
            assertThat(messages.get(0).getKeyVersion()).isEqualTo(1);
        }

        @Test
        void getMessages_withParentMessageId_isMapped() {
            var session = mock(CqlSession.class);
            var preparedStmt = mock(PreparedStatement.class);
            var boundStmt = mock(BoundStatement.class);
            when(session.prepare(anyString())).thenReturn(preparedStmt);
            when(preparedStmt.bind(any(Object[].class))).thenReturn(boundStmt);

            UUID msgId = Uuids.timeBased();
            UUID parentId = Uuids.timeBased();
            var row = mock(Row.class);
            when(row.getBoolean("deleted")).thenReturn(false);
            when(row.getUuid("message_id")).thenReturn(msgId);
            when(row.getLong("user_id")).thenReturn(10L);
            when(row.getString("username")).thenReturn("alice");
            when(row.getString("content")).thenReturn("Reply");
            when(row.getUuid("parent_message_id")).thenReturn(parentId);
            when(row.getBoolean("edited")).thenReturn(false);
            when(row.getString("hash")).thenReturn("hash");
            when(row.getString("prev_hash")).thenReturn("prevhash");
            when(row.isNull("key_version")).thenReturn(true);
            when(row.getString("message_type")).thenReturn(null);
            when(row.getString("metadata")).thenReturn(null);

            var rs = mock(ResultSet.class);
            when(rs.iterator()).thenReturn(List.of(row).iterator());
            when(session.execute(any(BoundStatement.class))).thenReturn(rs);

            var repo = new ScyllaMessageRepository(session);
            var messages = repo.getMessages(1L, "2026-03-20", null, 50);

            assertThat(messages.get(0).getParentMessageId()).isEqualTo(parentId.toString());
        }

        @Test
        void getRecentMessages_todayHasEnough_doesNotQueryYesterday() {
            var session = mock(CqlSession.class);
            var preparedStmt = mock(PreparedStatement.class);
            var boundStmt = mock(BoundStatement.class);
            when(session.prepare(anyString())).thenReturn(preparedStmt);
            when(preparedStmt.bind(any(Object[].class))).thenReturn(boundStmt);

            UUID msgId = Uuids.timeBased();
            var row = mock(Row.class);
            when(row.getBoolean("deleted")).thenReturn(false);
            when(row.getUuid("message_id")).thenReturn(msgId);
            when(row.getLong("user_id")).thenReturn(10L);
            when(row.getString("username")).thenReturn("alice");
            when(row.getString("content")).thenReturn("Hello");
            when(row.getUuid("parent_message_id")).thenReturn(null);
            when(row.getBoolean("edited")).thenReturn(false);
            when(row.getString("hash")).thenReturn("hash");
            when(row.getString("prev_hash")).thenReturn("0");
            when(row.isNull("key_version")).thenReturn(true);
            when(row.getString("message_type")).thenReturn(null);
            when(row.getString("metadata")).thenReturn(null);

            var rs = mock(ResultSet.class);
            when(rs.iterator()).thenReturn(List.of(row).iterator());
            when(session.execute(any(BoundStatement.class))).thenReturn(rs);

            var repo = new ScyllaMessageRepository(session);
            var messages = repo.getRecentMessages(1L, 1);

            assertThat(messages).hasSize(1);
        }

        @Test
        void getRecentMessages_todayEmpty_queriesYesterday() {
            var session = mock(CqlSession.class);
            var preparedStmt = mock(PreparedStatement.class);
            var boundStmt = mock(BoundStatement.class);
            when(session.prepare(anyString())).thenReturn(preparedStmt);
            when(preparedStmt.bind(any(Object[].class))).thenReturn(boundStmt);

            var emptyRs = mock(ResultSet.class);
            when(emptyRs.iterator()).thenReturn(Collections.emptyIterator());
            when(session.execute(any(BoundStatement.class))).thenReturn(emptyRs);

            var repo = new ScyllaMessageRepository(session);
            var messages = repo.getRecentMessages(1L, 10);

            assertThat(messages).isEmpty();
        }

        @Test
        void verifyChain_emptyChannel_returnsIntactWithZeroCounts() {
            var session = mock(CqlSession.class);
            var preparedStmt = mock(PreparedStatement.class);
            when(session.prepare(anyString())).thenReturn(preparedStmt);

            var emptyRs = mock(ResultSet.class);
            when(emptyRs.iterator()).thenReturn(Collections.emptyIterator());
            when(session.execute(anyString(), any(Object[].class))).thenReturn(emptyRs);

            var repo = new ScyllaMessageRepository(session);
            var result = repo.verifyChain(1L, Instant.now());

            assertThat(result.messageCount()).isZero();
            assertThat(result.validCount()).isZero();
            assertThat(result.intact()).isTrue();
            assertThat(result.firstBrokenMessageId()).isNull();
        }

        @Test
        void verifyChain_validChain_returnsIntact() {
            var session = mock(CqlSession.class);
            var preparedStmt = mock(PreparedStatement.class);
            when(session.prepare(anyString())).thenReturn(preparedStmt);

            UUID msgId = Uuids.timeBased();
            String content = "Hello";
            String prevHash = "0";
            String hash = ScyllaMessageRepository.computeMessageHash(prevHash, 1L, 10L, msgId, content);

            var row = mock(Row.class);
            when(row.getBoolean("deleted")).thenReturn(false);
            when(row.getUuid("message_id")).thenReturn(msgId);
            when(row.getLong("user_id")).thenReturn(10L);
            when(row.getString("content")).thenReturn(content);
            when(row.getString("hash")).thenReturn(hash);
            when(row.getString("prev_hash")).thenReturn(prevHash);

            var rs = mock(ResultSet.class);
            when(rs.iterator()).thenReturn(List.of(row).iterator());
            when(session.execute(anyString(), any(Object[].class))).thenReturn(rs);

            var repo = new ScyllaMessageRepository(session);
            var result = repo.verifyChain(1L, Instant.now());

            assertThat(result.messageCount()).isEqualTo(1);
            assertThat(result.validCount()).isEqualTo(1);
            assertThat(result.intact()).isTrue();
        }

        @Test
        void verifyChain_brokenHash_reportsFirstBroken() {
            var session = mock(CqlSession.class);
            var preparedStmt = mock(PreparedStatement.class);
            when(session.prepare(anyString())).thenReturn(preparedStmt);

            UUID msgId = Uuids.timeBased();
            var row = mock(Row.class);
            when(row.getBoolean("deleted")).thenReturn(false);
            when(row.getUuid("message_id")).thenReturn(msgId);
            when(row.getLong("user_id")).thenReturn(10L);
            when(row.getString("content")).thenReturn("Hello");
            when(row.getString("hash")).thenReturn("wrong-hash");
            when(row.getString("prev_hash")).thenReturn("0");

            var rs = mock(ResultSet.class);
            when(rs.iterator()).thenReturn(List.of(row).iterator());
            when(session.execute(anyString(), any(Object[].class))).thenReturn(rs);

            var repo = new ScyllaMessageRepository(session);
            var result = repo.verifyChain(1L, Instant.now());

            assertThat(result.messageCount()).isEqualTo(1);
            assertThat(result.validCount()).isZero();
            assertThat(result.intact()).isFalse();
            assertThat(result.firstBrokenMessageId()).isEqualTo(msgId.toString());
        }

        @Test
        void verifyChain_nullHash_treatsAsValid() {
            var session = mock(CqlSession.class);
            var preparedStmt = mock(PreparedStatement.class);
            when(session.prepare(anyString())).thenReturn(preparedStmt);

            UUID msgId = Uuids.timeBased();
            var row = mock(Row.class);
            when(row.getBoolean("deleted")).thenReturn(false);
            when(row.getUuid("message_id")).thenReturn(msgId);
            when(row.getLong("user_id")).thenReturn(10L);
            when(row.getString("content")).thenReturn("Hello");
            when(row.getString("hash")).thenReturn(null);
            when(row.getString("prev_hash")).thenReturn("0");

            var rs = mock(ResultSet.class);
            when(rs.iterator()).thenReturn(List.of(row).iterator());
            when(session.execute(anyString(), any(Object[].class))).thenReturn(rs);

            var repo = new ScyllaMessageRepository(session);
            var result = repo.verifyChain(1L, Instant.now());

            assertThat(result.messageCount()).isEqualTo(1);
            assertThat(result.validCount()).isEqualTo(1);
            assertThat(result.intact()).isTrue();
        }

        @Test
        void verifyChain_brokenPrevHash_reportsFirstBroken() {
            var session = mock(CqlSession.class);
            var preparedStmt = mock(PreparedStatement.class);
            when(session.prepare(anyString())).thenReturn(preparedStmt);

            UUID msgId = Uuids.timeBased();
            var row = mock(Row.class);
            when(row.getBoolean("deleted")).thenReturn(false);
            when(row.getUuid("message_id")).thenReturn(msgId);
            when(row.getLong("user_id")).thenReturn(10L);
            when(row.getString("content")).thenReturn("Hello");
            when(row.getString("hash")).thenReturn("somehash");
            when(row.getString("prev_hash")).thenReturn("wrong");

            var rs = mock(ResultSet.class);
            when(rs.iterator()).thenReturn(List.of(row).iterator());
            when(session.execute(anyString(), any(Object[].class))).thenReturn(rs);

            var repo = new ScyllaMessageRepository(session);
            var result = repo.verifyChain(1L, Instant.now());

            assertThat(result.messageCount()).isEqualTo(1);
            assertThat(result.validCount()).isZero();
            assertThat(result.intact()).isFalse();
            assertThat(result.firstBrokenMessageId()).isEqualTo(msgId.toString());
        }

        @Test
        void verifyChain_deletedMessages_areSkipped() {
            var session = mock(CqlSession.class);
            var preparedStmt = mock(PreparedStatement.class);
            when(session.prepare(anyString())).thenReturn(preparedStmt);

            var deletedRow = mock(Row.class);
            when(deletedRow.getBoolean("deleted")).thenReturn(true);

            var rs = mock(ResultSet.class);
            when(rs.iterator()).thenReturn(List.of(deletedRow).iterator());
            when(session.execute(anyString(), any(Object[].class))).thenReturn(rs);

            var repo = new ScyllaMessageRepository(session);
            var result = repo.verifyChain(1L, Instant.now());

            assertThat(result.messageCount()).isZero();
            assertThat(result.intact()).isTrue();
        }

        @Test
        void deleteMessagesByChannel_deletesChainHead() {
            var session = mock(CqlSession.class);
            var preparedStmt = mock(PreparedStatement.class);
            when(session.prepare(anyString())).thenReturn(preparedStmt);
            when(session.execute(anyString(), any(Object[].class))).thenReturn(mock(ResultSet.class));

            var repo = new ScyllaMessageRepository(session);
            repo.deleteMessagesByChannel(1L, Instant.now());

            verify(session).execute("DELETE FROM chain_heads WHERE channel_id = ?", 1L);
        }
    }

    /** Helper to avoid unused inner class warning */
    private static class ScyllaMessageRepositoryWithSession extends ScyllaMessageRepository {
        ScyllaMessageRepositoryWithSession(CqlSession session) {
            super(session);
        }
    }
}
