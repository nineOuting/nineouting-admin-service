package com.nineouting.backend.admin.auth;

import com.nineouting.backend.admin.config.AdminProperties;
import com.nineouting.backend.admin.error.AdminException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 관리자 로그인, 로그아웃, 지금 로그인 상태 */
@RestController
@RequestMapping("/api/admin/session")
public class SessionController {

    private static final Logger log = LoggerFactory.getLogger(SessionController.class);
    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();

    private final AdminProperties properties;
    private final SessionTokens tokens;
    private final LoginGuard guard;

    public SessionController(AdminProperties properties, SessionTokens tokens, LoginGuard guard) {
        this.properties = properties;
        this.tokens = tokens;
        this.guard = guard;
    }

    public record LoginRequest(@NotNull String username, @NotNull String password) {
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        Instant lockedUntil = guard.lockedUntil().orElse(null);
        if (lockedUntil != null) {
            throw new AdminException(HttpStatus.TOO_MANY_REQUESTS, "LOGIN_LOCKED",
                    "로그인을 여러 번 틀려 잠겼습니다. " + lockedUntil + " 뒤에 다시 시도해 주세요");
        }
        // 이름이 틀려도 비밀번호 확인은 한다 (응답 시간으로 이름을 알아내지 못하게)
        boolean passwordMatches = request.password() != null && ENCODER.matches(request.password(), properties.passwordHash());
        boolean usernameMatches = properties.username().equals(request.username());
        if (!passwordMatches || !usernameMatches) {
            guard.failed();
            log.warn("관리자 로그인 실패 (ip={})", http.getRemoteAddr());
            throw new AdminException(HttpStatus.UNAUTHORIZED, "LOGIN_FAILED", "아이디 또는 비밀번호가 맞지 않습니다");
        }
        guard.succeeded();
        log.info("관리자 로그인 (ip={})", http.getRemoteAddr());
        String token = tokens.issue(properties.username());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie(token, properties.sessionTtl()).toString())
                .body(Map.of("username", properties.username()));
    }

    @DeleteMapping
    public ResponseEntity<Void> logout() {
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString()).build();
    }

    @GetMapping
    public Map<String, Object> me(HttpServletRequest http) {
        SessionTokens.Session session = (SessionTokens.Session) http.getAttribute(AuthInterceptor.SESSION_ATTRIBUTE);
        return Map.of("username", session.username(), "expiresAt", session.expiresAt().toString());
    }

    private ResponseCookie cookie(String value, Duration maxAge) {
        return ResponseCookie.from(AuthInterceptor.COOKIE, value)
                .httpOnly(true)
                .secure(properties.cookieSecure())
                .sameSite("Strict")
                .path("/api/admin")
                .maxAge(maxAge)
                .build();
    }
}
