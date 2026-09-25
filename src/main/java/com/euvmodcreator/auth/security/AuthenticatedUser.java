package com.euvmodcreator.auth.security;

import java.util.UUID;

public record AuthenticatedUser(UUID userId, UUID sessionId) {
}
