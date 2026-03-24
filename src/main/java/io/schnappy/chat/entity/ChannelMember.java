package io.schnappy.chat.entity;

import jakarta.persistence.*;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "channel_members", uniqueConstraints = @UniqueConstraint(columnNames = {"channel_id", "user_uuid"}))
@Getter
@Setter
@NoArgsConstructor
public class ChannelMember {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "channel_id", nullable = false)
    private Long channelId;

    @JsonIgnore
    @Column(name = "user_uuid", nullable = false)
    private UUID userUuid;

    @Column(name = "joined_at", nullable = false, updatable = false)
    private Instant joinedAt = Instant.now();

    @Column(name = "last_read_at", nullable = false)
    private Instant lastReadAt = Instant.now();
}
