package com.euvmodcreator.auth;

import com.euvmodcreator.auth.dto.RegisterRequest;
import com.euvmodcreator.auth.model.User;
import com.euvmodcreator.auth.model.UserAuth;
import com.euvmodcreator.auth.repository.UserAuthRepository;
import com.euvmodcreator.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
class AuthService {

    private final UserRepository userRepository;

    private final UserAuthRepository userAuthRepository;

    private final PasswordEncoder passwordEncoder;

    @Transactional
    User register(RegisterRequest registerRequest) {
        boolean userExists = userRepository.existsByUsernameIgnoreCase(registerRequest.username());
        if (userExists) {
            throw new UsernameTakenException();
        }

        User user = new User();
        user.setUsername(registerRequest.username());
        User savedUser = userRepository.save(user);

        UserAuth userAuth = new UserAuth();
        userAuth.setPasswordHash(passwordEncoder.encode(registerRequest.password()));
        userAuth.setUserId(savedUser.getId());
        userAuthRepository.save(userAuth);

        return savedUser;
    }

}
