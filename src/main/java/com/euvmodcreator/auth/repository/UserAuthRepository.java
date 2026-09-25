package com.euvmodcreator.auth.repository;

import com.euvmodcreator.auth.model.UserAuth;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface UserAuthRepository extends JpaRepository<UserAuth, UUID> {

    @Query("""
            select new com.euvmodcreator.auth.repository.LoginCredentials(u, a.passwordHash)
                        from User u
                             join UserAuth a on a.userId = u.id
                        where lower(u.username) = lower(:username)
            """)
    Optional<LoginCredentials> findLoginCredentials(String username);
}
