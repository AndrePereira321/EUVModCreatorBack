package com.euvmodcreator.auth.repository;

import com.euvmodcreator.auth.entity.UserAuth;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import java.util.Optional;
import java.util.UUID;

// Repository, not JpaRepository: findAll() and findById() would hand the password hashes to any caller. Only what
// the service needs is declared, so findLoginCredentials stays the one query that reads a hash.
public interface UserAuthRepository extends Repository<UserAuth, UUID> {

    UserAuth save(UserAuth userAuth);

    @Query("""
            select new com.euvmodcreator.auth.repository.LoginCredentials(u, a.passwordHash)
                        from User u
                             join UserAuth a on a.userId = u.id
                        where lower(u.username) = lower(:username)
            """)
    Optional<LoginCredentials> findLoginCredentials(String username);
}
