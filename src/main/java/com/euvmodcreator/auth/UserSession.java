package com.euvmodcreator.auth;

import com.euvmodcreator.database.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "user_sessions")
@Getter
@Setter
@NoArgsConstructor
public class UserSession extends BaseEntity {

    private UUID userId;

    private String refreshTokenHash;

    private Instant expiresAt;

    private Instant revokedAt;

}
