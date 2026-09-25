package com.euvmodcreator.auth.repository;

import com.euvmodcreator.auth.model.UserAuth;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface UserAuthRepository extends JpaRepository<UserAuth, UUID> {
}
