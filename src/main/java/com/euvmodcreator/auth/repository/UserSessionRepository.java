package com.euvmodcreator.auth.repository;

import com.euvmodcreator.auth.entity.UserSession;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface UserSessionRepository extends JpaRepository<UserSession, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<UserSession> findByRefreshTokenHash(String refreshTokenHash);

    @Modifying
    @Transactional
    @Query("""
            update UserSession s set s.revokedAt = :now, s.updatedAt = :now
            where s.refreshTokenHash = :refreshTokenHash and s.revokedAt is null
            """)
    int revokeByRefreshTokenHash(String refreshTokenHash, Instant now);
}
