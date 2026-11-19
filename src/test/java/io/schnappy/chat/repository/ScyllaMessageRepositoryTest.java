package io.schnappy.chat.repository;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.ProtocolVersion;
import com.datastax.oss.driver.api.core.cql.BoundStatement;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.type.DataTypes;
import com.datastax.oss.driver.api.core.type.codec.registry.CodecRegistry;
import com.datastax.oss.driver.api.core.type.reflect.GenericType;
import com.datastax.oss.driver.api.core.uuid.Uuids;
import io.schnappy.chat.dto.ChatMessageDto;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScyllaMessageRepositoryTest {

    private static final UUID USER_UUID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

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
        String hash1 = ScyllaMessageRepository.computeMessageHash("prevhash", 1L, USER_UUID, messageId, "Hello");
        String hash2 = ScyllaMessageRepository.computeMessageHash("prevhash", 1L, USER_UUID, messageId, "Hello");
        assertThat(hash1).isEqualTo(hash2);
    }

    @Test
    void computeMessageHash_differentContent_producesDifferentHash() {
        UUID messageId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        String hash1 = ScyllaMessageRepository.computeMessageHash("0", 1L, USER_UUID, messageId, "Hello");
        String hash2 = ScyllaMessageRepository.computeMessageHash("0", 1L, USER_UUID, messageId, "World");
        assertThat(hash1).isNotEqualTo(hash2);
    }

    @Test
    void computeMessageHash_differentPrevHash_producesDifferentHash() {
        UUID messageId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        String hash1 = ScyllaMessageRepository.computeMessageHash("abc", 1L, USER_UUID, messageId, "Hello");
        String hash2 = ScyllaMessageRepository.computeMessageHash("def", 1L, USER_UUID, messageId, "Hello");
        assertThat(hash1).isNotEqualTo(hash2);
    }

    @Test
    void computeMessageHash_differentChannelId_producesDifferentHash() {
        UUID messageId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        String hash1 = ScyllaMessageRepository.computeMessageHash("0", 1L, USER_UUID, messageId, "Hello");
        String hash2 = ScyllaMessageRepository.computeMessageHash("0", 2L, USER_UUID, messageId, "Hello");
        assertThat(hash1).isNotEqualTo(hash2);
    }

    @Test
    void computeMessageHash_returnsHexString() {
        UUID messageId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        String hash = ScyllaMessageRepository.computeMessageHash("0", 1L, USER_UUID, messageId, "Hello");
        assertThat(hash).hasSize(64).matches("[0-9a-f]+");
    }

    @Test
    void computeEditHash_deterministicForSameInputs() {
        UUID editId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        String hash1 = ScyllaMessageRepository.computeEditHash("origHash", editId, USER_UUID, "edited content");
        String hash2 = ScyllaMessageRepository.computeEditHash("origHash", editId, USER_UUID, "edited content");
        assertThat(hash1).isEqualTo(hash2);
    }

    @Test
    void computeEditHash_differentContent_producesDifferentHash() {
        UUID editId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        String hash1 = ScyllaMessageRepository.computeEditHash("origHash", editId, USER_UUID, "v1");
        String hash2 = ScyllaMessageRepository.computeEditHash("origHash", editId, USER_UUID, "v2");
        assertThat(hash1).isNotEqualTo(hash2);
    }

    @Test
    void computeEditHash_differentOriginalHash_producesDifferentHash() {
        UUID editId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        String hash1 = ScyllaMessageRepository.computeEditHash("hash1", editId, USER_UUID, "content");
        String hash2 = ScyllaMessageRepository.computeEditHash("hash2", editId, USER_UUID, "content");
        assertThat(hash1).isNotEqualTo(hash2);
    }

    @Test
    void computeEditHash_returnsHexString() {
        UUID editId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        String hash = ScyllaMessageRepository.computeEditHash("origHash", editId, USER_UUID, "content");
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
        var er = new ScyllaMessageRepository.EditRecord("edit-1", USER_UUID, "content", "hash123", now);
        assertThat(er.editId()).isEqualTo("edit-1");
        assertThat(er.userUuid()).isEqualTo(USER_UUID);
        assertThat(er.content()).isEqualTo("content");
        assertThat(er.hash()).isEqualTo("hash123");
        assertThat(er.createdAt()).isEqualTo(now);
    }

    @Test
    void rowId_theSameOnEveryTry_atTheProducersTime_aTimeuuidTheDriverTakes() {
        Instant created = Instant.parse("2026-11-19T12:34:56.789Z");
        var msg = ChatMessageDto.builder().messageId("6f9619ff-8b86-4011-a6b0-f2c0f1c0d2e3").createdAt(created).build();
        long lsb = ScyllaMessageRepository.rowLsb(msg);

        UUID id = ScyllaMessageRepository.rowId(msg, null, lsb);

        assertThat(ScyllaMessageRepository.rowId(msg, null, ScyllaMessageRepository.rowLsb(msg))).as("a retry's id")
                .isEqualTo(id);
        assertThat(id.version()).isEqualTo(1);
        assertThat(id.variant()).isEqualTo(2);
        assertThat(Uuids.unixTimestamp(id)).isEqualTo(created.toEpochMilli());
        // the column's own codec: a non-v1 UUID is refused there
        assertThat(CodecRegistry.DEFAULT.codecFor(DataTypes.TIMEUUID, id).encode(id, ProtocolVersion.DEFAULT)).isNotNull();
    }

    @Test
    void rowId_aProducerClockBehindTheHead_oneTickAfterIt() {
        // rows are walked in id order and must be in chain order: a message chained after the head sorts after it, its
        // producer's clock earlier or not (a second producer, a clock step, the head an old write-time id)
        Instant created = Instant.parse("2026-11-19T12:00:00Z");
        UUID head = ScyllaMessageRepository.rowId(
                ChatMessageDto.builder().messageId(UUID.randomUUID().toString()).createdAt(created.plusMillis(5)).build(),
                null, 1L | Long.MIN_VALUE);
        var msg = ChatMessageDto.builder().messageId(UUID.randomUUID().toString()).createdAt(created).build();

        UUID id = ScyllaMessageRepository.rowId(msg, head, ScyllaMessageRepository.rowLsb(msg));

        assertThat(id.timestamp()).isEqualTo(head.timestamp() + 1);
    }

    @Test
    void rowId_theHeadOlder_theProducersTime() {
        Instant created = Instant.parse("2026-11-19T12:00:00Z");
        UUID head = Uuids.startOf(created.minusSeconds(60).toEpochMilli());
        var msg = ChatMessageDto.builder().messageId(UUID.randomUUID().toString()).createdAt(created).build();

        UUID id = ScyllaMessageRepository.rowId(msg, head, ScyllaMessageRepository.rowLsb(msg));

        assertThat(Uuids.unixTimestamp(id)).isEqualTo(created.toEpochMilli());
    }

    @Test
    void msbOf_theTimeItWasMadeFor() {
        long ticks = 0x01EF_0123_4567_89ABL;

        assertThat(new UUID(ScyllaMessageRepository.msbOf(ticks), Long.MIN_VALUE).timestamp()).isEqualTo(ticks);
    }

    @Test
    void rowLsb_twoMessagesInOneMillisecond_twoIds() {
        var a = ChatMessageDto.builder().messageId(UUID.randomUUID().toString()).build();
        var b = ChatMessageDto.builder().messageId(UUID.randomUUID().toString()).build();

        assertThat(ScyllaMessageRepository.rowLsb(a)).isNotEqualTo(ScyllaMessageRepository.rowLsb(b));
    }

    @Test
    void rowLsb_timeBasedProducerIds_ofOneJvm_twoIds() {
        // a time-based id's low bits are its JVM's clock sequence and node, the same in every id it makes: taken as
        // they are, two messages of one millisecond would share a row id (and the second be read as stored already)
        UUID first = Uuids.timeBased();
        UUID second = Uuids.timeBased();
        assertThat(first.getLeastSignificantBits()).isEqualTo(second.getLeastSignificantBits());

        assertThat(ScyllaMessageRepository.rowLsb(ChatMessageDto.builder().messageId(first.toString()).build()))
                .isNotEqualTo(ScyllaMessageRepository.rowLsb(ChatMessageDto.builder().messageId(second.toString()).build()));
    }

    @Test
    void rowLsb_noProducerId_aFreshOne_noCreatedAt_noFailure() {
        var noId = ChatMessageDto.builder().build();

        assertThat(ScyllaMessageRepository.rowLsb(noId)).isNotEqualTo(ScyllaMessageRepository.rowLsb(noId));
        assertThat(ScyllaMessageRepository.rowId(noId, null, ScyllaMessageRepository.rowLsb(noId)).version()).isEqualTo(1);
    }

    /** A session whose statements are told apart: the channel's row, the chain head's read and its write. */
    static final class Store {
        final CqlSession session = mock(CqlSession.class);
        final PreparedStatement byChannel = mock(PreparedStatement.class);
        final PreparedStatement headRead = mock(PreparedStatement.class);
        final PreparedStatement headWrite = mock(PreparedStatement.class);
        final ResultSet headRs = mock(ResultSet.class);
        final ScyllaMessageRepository repo;

        Store() {
            var other = mock(PreparedStatement.class);
            var bound = mock(BoundStatement.class);
            var headBound = mock(BoundStatement.class);
            when(session.prepare(anyString())).thenReturn(other);
            when(session.prepare(startsWith("INSERT INTO messages_by_channel "))).thenReturn(byChannel);
            when(session.prepare(startsWith("SELECT hash, message_id FROM chain_heads"))).thenReturn(headRead);
            when(session.prepare(startsWith("INSERT INTO chain_heads "))).thenReturn(headWrite);
            for (PreparedStatement st : List.of(other, byChannel, headWrite)) {
                when(st.bind(any(Object[].class))).thenReturn(bound);
            }
            when(headRead.bind(any(Object[].class))).thenReturn(headBound);
            var nothing = mock(ResultSet.class);
            when(session.execute(any(BoundStatement.class))).thenReturn(nothing);
            when(session.execute(headBound)).thenReturn(headRs);
            repo = new ScyllaMessageRepository(session);
        }

        /** The chain head names that row, with that hash. */
        void head(UUID id, String hash) {
            var row = mock(Row.class);
            when(row.getUuid("message_id")).thenReturn(id);
            when(row.getString("hash")).thenReturn(hash);
            when(headRs.one()).thenReturn(row);
        }

        /** Every row the channel's insert bound, in order. */
        List<Object[]> rows() {
            ArgumentCaptor<Object[]> rows = ArgumentCaptor.forClass(Object[].class);
            verify(byChannel, atLeast(0)).bind(rows.capture());
            return rows.getAllValues();
        }
    }

    static ChatMessageDto message(Instant createdAt, String content) {
        return ChatMessageDto.builder().messageId(UUID.randomUUID().toString()).createdAt(createdAt)
                .channelId(1L).userUuid(USER_UUID).username("alice").content(content).build();
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
                    .channelId(1L).userUuid(USER_UUID).username("alice").content("Hello").build();

            UUID messageId = repo.saveMessage(msg, null);

            assertThat(messageId).isNotNull();
        }

        @Test
        void saveMessage_aReply_itsParentBoundAsTheTextColumnIs() {
            // parent_message_id is text: a UUID bound there has no codec in the driver (CodecNotFoundException
            // [TEXT <-> java.util.UUID]) and the reply was never stored - a mocked bind takes any type, so the bound
            // value is put to the driver's own codec registry
            var session = mock(CqlSession.class);
            var anyStmt = mock(PreparedStatement.class);
            var byChannel = mock(PreparedStatement.class);
            var boundStmt = mock(BoundStatement.class);
            when(session.prepare(anyString())).thenReturn(anyStmt);
            when(session.prepare(startsWith("INSERT INTO messages_by_channel "))).thenReturn(byChannel);
            when(anyStmt.bind(any(Object[].class))).thenReturn(boundStmt);
            when(byChannel.bind(any(Object[].class))).thenReturn(boundStmt);
            var noChainHead = mock(ResultSet.class);
            when(session.execute(any(BoundStatement.class))).thenReturn(noChainHead);
            var repo = new ScyllaMessageRepository(session);
            UUID parent = UUID.randomUUID();
            var msg = ChatMessageDto.builder().messageId(UUID.randomUUID().toString()).createdAt(Instant.now())
                    .channelId(1L).userUuid(USER_UUID).username("alice").content("a reply").build();

            repo.saveMessage(msg, parent);

            ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
            verify(byChannel).bind(args.capture());
            Object bound = args.getValue()[6];
            assertThat(bound).isEqualTo(parent.toString());
            assertThat(CodecRegistry.DEFAULT.codecFor(DataTypes.TEXT, bound).getJavaType())
                    .isEqualTo(GenericType.STRING);
        }

        @Test
        void saveMessage_aRetryAfterItsLastWrite_writesNothing() {
            // the chain head names this message already: it was stored whole - chained again, to itself
            var store = new Store();
            var msg = message(Instant.now(), "Hello");
            UUID stored = ScyllaMessageRepository.rowId(msg, null, ScyllaMessageRepository.rowLsb(msg));
            store.head(stored, "h-stored");

            assertThat(store.repo.saveMessage(msg, null)).isEqualTo(stored);
            verify(store.byChannel, never()).bind(any(Object[].class));
            verify(store.headWrite, never()).bind(any(Object[].class));
        }

        @Test
        void saveMessage_afterAnotherMessage_chainedToTheHead_andTheHeadMovedToIt() {
            var store = new Store();
            Instant created = Instant.parse("2026-11-19T12:00:00Z");
            UUID prior = Uuids.startOf(created.plusMillis(3).toEpochMilli());  // the head: a clock ahead of this one
            store.head(prior, "h-prior");
            var msg = message(created, "Hello");

            UUID id = store.repo.saveMessage(msg, null);

            Object[] row = store.rows().getFirst();
            assertThat(row[2]).isEqualTo(id);
            assertThat(id.timestamp()).as("after the head: the rows' order is the chain's").isGreaterThan(prior.timestamp());
            assertThat(row[8]).as("prev_hash").isEqualTo("h-prior");
            String hash = ScyllaMessageRepository.computeMessageHash("h-prior", 1L, USER_UUID, id, "Hello");
            assertThat(row[7]).as("hash").isEqualTo(hash);
            verify(store.headWrite).bind(1L, hash, id);
        }

        @Test
        void saveMessage_aReplayOfAnOlderMessage_appendedAfterTheHead_neverOverItsRow() {
            // M1 stored, M2 after it; M1 delivered again (a replay, offsets lost): a second row at the end, the chain
            // whole - written over M1's row, M1 would chain to M2 and M2's own link to M1 would break
            var store = new Store();
            Instant created = Instant.parse("2026-11-19T12:00:00Z");
            var m1 = message(created, "first");
            UUID m1Row = store.repo.saveMessage(m1, null);
            UUID m2Row = Uuids.startOf(created.plusMillis(1).toEpochMilli());
            store.head(m2Row, "h-m2");

            UUID again = store.repo.saveMessage(m1, null);

            assertThat(again).isNotEqualTo(m1Row);
            assertThat(again.timestamp()).isGreaterThan(m2Row.timestamp());
            assertThat(store.rows()).hasSize(2);
            assertThat(store.rows().get(1)[8]).as("chained to the head, M2").isEqualTo("h-m2");
        }

        @Test
        void saveMessage_aRetryAfterAHalfWrite_theSameRowAgain() {
            // the row landed, the chain head did not (ScyllaDB gone between them): the head unchanged, the retry writes
            // that row again - the same id, prev_hash and hash, an upsert of itself
            var store = new Store();
            store.head(Uuids.startOf(Instant.parse("2026-11-19T11:00:00Z").toEpochMilli()), "h-prior");
            var msg = message(Instant.parse("2026-11-19T12:00:00Z"), "Hello");

            UUID first = store.repo.saveMessage(msg, null);
            UUID retry = store.repo.saveMessage(msg, null);

            assertThat(retry).isEqualTo(first);
            assertThat(store.rows()).hasSize(2);
            assertThat(store.rows().get(1)).containsExactly(store.rows().get(0));
        }

        @Test
        void saveMessage_neitherEditedNorDeletedWritten() {
            // a retry's insert would set them back: an edit made between its tries undone
            var store = new Store();
            verify(store.session).prepare(argThat((String sql) -> sql.startsWith("INSERT INTO messages_by_channel ")
                    && !sql.contains("edited") && !sql.contains("deleted")));

            store.repo.saveMessage(message(Instant.now(), "Hello"), null);

            assertThat(store.rows().getFirst()).hasSize(12);
        }

        @Test
        void saveMessage_theBucketOfTheRowIdsDay() {
            // the day an edit finds the row by (its id's time) - and a retry past midnight UTC writes the row it wrote
            var store = new Store();
            var msg = message(Instant.parse("2026-03-20T23:59:59Z"), "Hello");

            UUID id = store.repo.saveMessage(msg, null);

            assertThat(store.rows().getFirst()[1]).isEqualTo("2026-03-20");
            assertThat(ScyllaMessageRepository.bucketForDate(Instant.ofEpochMilli(Uuids.unixTimestamp(id))))
                    .isEqualTo("2026-03-20");
        }

        @Test
        void saveMessage_theHeadPastMidnight_theBucketTheRowIdsDay_notTheProducersDay() {
            // the row's id is one tick after the head, on the next day: a bucket of createdAt's day would put the row
            // where an edit (it looks by the id's day) never finds it - and its edited flag would make a ghost row
            var store = new Store();
            store.head(Uuids.startOf(Instant.parse("2026-03-21T00:00:00Z").toEpochMilli()), "h-midnight");
            var msg = message(Instant.parse("2026-03-20T23:59:59Z"), "Hello");

            store.repo.saveMessage(msg, null);

            assertThat(store.rows().getFirst()[1]).isEqualTo("2026-03-21");
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
            var nothing = mock(ResultSet.class);
            when(session.execute(any(BoundStatement.class))).thenReturn(nothing);

            var repo = new ScyllaMessageRepository(session);
            repo.saveEdit(1L, "2026-03-20", UUID.randomUUID(), USER_UUID, "edited", "origHash");

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
            when(row.getUuid("user_uuid")).thenReturn(USER_UUID);
            when(row.getString("content")).thenReturn("edited text");
            when(row.getString("hash")).thenReturn("edithash123");

            var rs = mock(ResultSet.class);
            when(rs.iterator()).thenReturn(List.of(row).iterator());
            when(session.execute(any(BoundStatement.class))).thenReturn(rs);

            var repo = new ScyllaMessageRepository(session);
            var edits = repo.getEdits(1L, "2026-03-20", UUID.randomUUID());

            assertThat(edits).hasSize(1);
            assertThat(edits.get(0).userUuid()).isEqualTo(USER_UUID);
            assertThat(edits.get(0).content()).isEqualTo("edited text");
            assertThat(edits.get(0).hash()).isEqualTo("edithash123");
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
            when(row.getUuid("user_uuid")).thenReturn(USER_UUID);
            when(row.getString("username")).thenReturn("alice");
            when(row.getString("content")).thenReturn("Hello");
            when(row.getString("parent_message_id")).thenReturn(null);
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
            assertThat(messages.get(0).getUserUuid()).isEqualTo(USER_UUID);
            assertThat(messages.get(0).getContent()).isEqualTo("Hello");
            assertThat(messages.get(0).getEditedContent()).isNull();
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
            String hash = ScyllaMessageRepository.computeMessageHash(prevHash, 1L, USER_UUID, msgId, content);

            var row = mock(Row.class);
            when(row.getBoolean("deleted")).thenReturn(false);
            when(row.getUuid("message_id")).thenReturn(msgId);
            when(row.getUuid("user_uuid")).thenReturn(USER_UUID);
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
        void deleteMessagesByChannel_deletesChainHead() {
            var session = mock(CqlSession.class);
            var preparedStmt = mock(PreparedStatement.class);
            when(session.prepare(anyString())).thenReturn(preparedStmt);
            var nothing = mock(ResultSet.class);
            when(session.execute(anyString(), any(Object[].class))).thenReturn(nothing);

            var repo = new ScyllaMessageRepository(session);
            repo.deleteMessagesByChannel(1L, Instant.now());

            verify(session).execute("DELETE FROM chain_heads WHERE channel_id = ?", 1L);
        }
    }
}
