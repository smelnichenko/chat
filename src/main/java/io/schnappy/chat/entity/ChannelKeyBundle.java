package io.schnappy.chat.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "channel_key_bundles",
    uniqueConstraints = @UniqueConstraint(columnNames = {"channel_id", "user_uuid", "key_version"}))
@Getter
@Setter
@NoArgsConstructor
public class ChannelKeyBundle {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "channel_id", nullable = false)
    private Long channelId;

    @Column(name = "user_uuid", nullable = false)
    private UUID userUuid;

    @Column(name = "key_version", nullable = false)
    private int keyVersion = 1;

    @Column(name = "encrypted_channel_key", nullable = false, columnDefinition = "TEXT")
    private String encryptedChannelKey;

    @Column(name = "wrapper_public_key", nullable = false, columnDefinition = "TEXT")
    private String wrapperPublicKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
