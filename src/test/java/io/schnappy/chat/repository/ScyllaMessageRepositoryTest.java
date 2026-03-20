package io.schnappy.chat.repository;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ScyllaMessageRepositoryTest {

    // --- bucketForDate ---

    @Test
    void bucketForDate_returnsUtcDate() {
        Instant instant = Instant.parse("2026-03-20T15:30:00Z");

        String bucket = ScyllaMessageRepository.bucketForDate(instant);

        assertThat(bucket).isEqualTo("2026-03-20");
    }

    @Test
    void bucketForDate_nearMidnightUtc_usesCorrectDate() {
        // Just before midnight UTC
        Instant instant = Instant.parse("2026-03-20T23:59:59Z");
        assertThat(ScyllaMessageRepository.bucketForDate(instant)).isEqualTo("2026-03-20");

        // Just after midnight UTC
        Instant afterMidnight = Instant.parse("2026-03-21T00:00:01Z");
        assertThat(ScyllaMessageRepository.bucketForDate(afterMidnight)).isEqualTo("2026-03-21");
    }

    @Test
    void bucketForDate_epoch_returnsEpochDate() {
        assertThat(ScyllaMessageRepository.bucketForDate(Instant.EPOCH)).isEqualTo("1970-01-01");
    }

    // --- computeMessageHash ---

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

        // SHA-256 produces 64 hex chars
        assertThat(hash).hasSize(64);
        assertThat(hash).matches("[0-9a-f]+");
    }

    // --- computeEditHash ---

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

        assertThat(hash).hasSize(64);
        assertThat(hash).matches("[0-9a-f]+");
    }
}
