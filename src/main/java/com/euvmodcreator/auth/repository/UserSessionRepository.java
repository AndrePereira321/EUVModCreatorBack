package com.euvmodcreator.auth.repository;

import com.euvmodcreator.auth.entity.UserSession;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.Optional;
import java.util.UUID;

public interface UserSessionRepository extends JpaRepository<UserSession, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<UserSession> findByRefreshTokenHash(String refreshTokenHash);
}
