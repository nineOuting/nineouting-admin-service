package com.nineouting.backend.admin.auth;

import com.nineouting.backend.admin.config.AdminProperties;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 로그인 실패를 센다. 관리자 계정이 하나라 계정 기준으로 센다. 정해진 횟수를 넘기면 잠깐 잠근다.
 * 파드 하나 기준이다 (DEV는 admin-service를 1개만 띄운다).
 */
@Component
public class LoginGuard {

    private final AdminProperties properties;
    private final Clock clock;
    private int failures;
    private Instant lockedUntil = Instant.EPOCH;

    public LoginGuard(AdminProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /** 잠겨 있으면 풀리는 시각 */
    public synchronized Optional<Instant> lockedUntil() {
        return lockedUntil.isAfter(clock.instant()) ? Optional.of(lockedUntil) : Optional.empty();
    }

    public synchronized void failed() {
        failures++;
        if (failures >= properties.maxLoginFailures()) {
            lockedUntil = clock.instant().plus(properties.lockDuration());
            failures = 0;
        }
    }

    public synchronized void succeeded() {
        failures = 0;
        lockedUntil = Instant.EPOCH;
    }
}
