package com.euvmodcreator.auth;

import com.euvmodcreator.auth.dto.UserResponse;
import com.euvmodcreator.auth.exception.InvalidAccessTokenException;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping("/me")
    UserResponse me(@CurrentUser AuthenticatedUser currentUser) {
        return userService.findUser(currentUser.userId())
                .map(UserResponse::from)
                .orElseThrow(InvalidAccessTokenException::new);
    }

}
