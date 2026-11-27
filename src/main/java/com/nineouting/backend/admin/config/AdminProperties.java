package com.nineouting.backend.admin.config;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 관리자 계정, 로그인 쿠키, 붙을 DB. 계정과 서명 키가 비어 있으면 시작하지 않는다 (잘못 열린 관리자 페이지를 막는다).
 */
@ConfigurationProperties(prefix = "nineouting.admin")
public record AdminProperties(
        String username,
        String passwordHash,
        String sessionSecret,
        Duration sessionTtl,
        boolean cookieSecure,
        List<String> allowedOrigins,
        int maxLoginFailures,
        Duration lockDuration,
        Map<String, Database> databases) {

    private static final int MIN_SECRET_BYTES = 32;

    public AdminProperties {
        require(username, "ADMIN_USERNAME");
        require(passwordHash, "ADMIN_PASSWORD_HASH");
        require(sessionSecret, "ADMIN_SESSION_SECRET");
        if (sessionSecret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("ADMIN_SESSION_SECRET는 32바이트 이상이어야 합니다");
        }
        if (!passwordHash.startsWith("$2")) {
            throw new IllegalStateException("ADMIN_PASSWORD_HASH는 BCrypt 해시여야 합니다");
        }
        sessionTtl = sessionTtl == null ? Duration.ofHours(8) : sessionTtl;
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
        maxLoginFailures = maxLoginFailures <= 0 ? 5 : maxLoginFailures;
        lockDuration = lockDuration == null ? Duration.ofMinutes(15) : lockDuration;
        // 주소가 빈 DB는 뺀다. 순서는 설정 순서 그대로
        Map<String, Database> configured = new LinkedHashMap<>();
        if (databases != null) {
            databases.forEach((name, db) -> {
                if (db != null && db.url() != null && !db.url().isBlank()) {
                    configured.put(name, db);
                }
            });
        }
        databases = Collections.unmodifiableMap(configured);
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + "가 비어 있습니다");
        }
    }

    /** DB 하나. 비밀번호는 비밀값으로 넣는다 */
    public record Database(String url, String username, String password) {

        @Override
        public String toString() {
            return "Database[url=" + url + ", username=" + username + "]";
        }
    }

    @Override
    public String toString() {
        return "AdminProperties[username=" + username + ", databases=" + databases.keySet() + "]";
    }
}
