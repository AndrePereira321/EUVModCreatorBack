package com.euvmodcreator.auth.repository;

import com.euvmodcreator.auth.model.UserSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface UserSessionRepository extends JpaRepository<UserSession, UUID> {
}
