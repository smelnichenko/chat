package io.schnappy.chat.entity;

import jakarta.persistence.*;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "user_keys")
@Getter
@Setter
@NoArgsConstructor
public class UserKeys {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_uuid", nullable = false, unique = true)
    private UUID userUuid;

    @Column(name = "public_key", nullable = false, columnDefinition = "TEXT")
    private String publicKey;

    @JsonIgnore
    @Column(name = "encrypted_private_key", nullable = false, columnDefinition = "TEXT")
    private String encryptedPrivateKey;

    @JsonIgnore
    @Column(name = "pbkdf2_salt", nullable = false, columnDefinition = "TEXT")
    private String pbkdf2Salt;

    @Column(name = "pbkdf2_iterations", nullable = false)
    private int pbkdf2Iterations = 600000;

    @Column(name = "key_version", nullable = false)
    private int keyVersion = 1;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
