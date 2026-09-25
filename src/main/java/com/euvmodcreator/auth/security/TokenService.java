package com.euvmodcreator.auth.security;

import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TokenService {

    static final String SESSION_ID_CLAIM = "sid";

    private static final SecureRandom secureRandom = new SecureRandom();

    private final AuthProperties properties;

    private final JwtEncoder jwtEncoder;

    public String issueAccessToken(UUID userId, UUID sessionId) {
        Assert.notNull(userId, "User must be saved before a token can be issued");
        Assert.notNull(sessionId, "Session must be saved before a token can be issued");

        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(userId.toString())
                .claim(SESSION_ID_CLAIM, sessionId.toString())
                .issuedAt(now)
                .expiresAt(now.plus(properties.accessTokenTtl()))
                .build();

        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();

        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    public RefreshToken newRefreshToken() {
        return newRefreshToken(Instant.now().plus(properties.refreshTokenTtl()));
    }

    public RefreshToken newRefreshToken(Instant expiresAt) {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);

        String value = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        return new RefreshToken(value, expiresAt);
    }

    public String hashRefreshToken(String token) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha256.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every JDK is required to support SHA-256", e);
        }
    }
}
