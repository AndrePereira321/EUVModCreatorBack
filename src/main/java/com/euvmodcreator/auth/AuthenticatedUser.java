package com.euvmodcreator.auth;

import java.util.UUID;

public record AuthenticatedUser(UUID userId, UUID sessionId) {
}
