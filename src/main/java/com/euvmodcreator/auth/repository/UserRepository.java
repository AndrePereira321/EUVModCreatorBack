package com.euvmodcreator.auth.repository;

import com.euvmodcreator.auth.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    // Not the derived existsByUsernameIgnoreCase: Spring Data compiles IgnoreCase to upper(), which can't use the
    // lower(username) unique index.
    @Query("select count(u) > 0 from User u where lower(u.username) = lower(:username)")
    boolean existsByUsernameIgnoreCase(String username);

}
