package com.euvmodcreator.auth;

import com.euvmodcreator.auth.model.UserSummary;
import com.euvmodcreator.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class UserService {

    private final UserRepository userRepository;

    Optional<UserSummary> findUser(UUID id) {
        return userRepository.findById(id).map(UserSummary::from);
    }
}
