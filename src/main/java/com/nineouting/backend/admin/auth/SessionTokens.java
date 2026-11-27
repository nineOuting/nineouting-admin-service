package com.nineouting.backend.admin.auth;

import com.nineouting.backend.admin.config.AdminProperties;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * 로그인 쿠키 값: base64url("사용자:만료초") + "." + base64url(HMAC-SHA256). 서버에 세션을 두지 않는다.
 */
@Component
public class SessionTokens {

    private static final String ALGORITHM = "HmacSHA256";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final AdminProperties properties;
    private final Clock clock;

    public SessionTokens(AdminProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public record Session(String username, Instant expiresAt) {
    }

    public String issue(String username) {
        Instant expiresAt = clock.instant().plus(properties.sessionTtl());
        String payload = ENCODER.encodeToString((username + ":" + expiresAt.getEpochSecond()).getBytes(StandardCharsets.UTF_8));
        return payload + "." + ENCODER.encodeToString(sign(payload));
    }

    /** 서명이 맞고 만료 전이고 지금 관리자 이름과 같을 때만 */
    public Optional<Session> verify(String token) {
        if (token == null) {
            return Optional.empty();
        }
        int dot = token.indexOf('.');
        if (dot <= 0) {
            return Optional.empty();
        }
        String payload = token.substring(0, dot);
        try {
            if (!MessageDigest.isEqual(sign(payload), DECODER.decode(token.substring(dot + 1)))) {
                return Optional.empty();
            }
            String decoded = new String(DECODER.decode(payload), StandardCharsets.UTF_8);
            int colon = decoded.lastIndexOf(':');
            String username = decoded.substring(0, colon);
            Instant expiresAt = Instant.ofEpochSecond(Long.parseLong(decoded.substring(colon + 1)));
            if (!expiresAt.isAfter(clock.instant()) || !username.equals(properties.username())) {
                return Optional.empty();
            }
            return Optional.of(new Session(username, expiresAt));
        } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
            return Optional.empty();
        }
    }

    private byte[] sign(String payload) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(properties.sessionSecret().getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }
}
