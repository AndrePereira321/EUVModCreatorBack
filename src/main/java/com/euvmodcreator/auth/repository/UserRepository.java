package com.euvmodcreator.auth.repository;

import com.euvmodcreator.auth.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    // Hand-written, under a name Spring Data can't derive: a derived existsByUsernameIgnoreCase compiles to upper(),
    // which can't use the lower(username) unique index, and would take over silently if the @Query were dropped.
    @Query("select count(u) > 0 from User u where lower(u.username) = lower(:username)")
    boolean usernameExists(String username);

}
