package com.nineouting.backend.admin.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 로그인 쿠키 만료, 잠금 시각에 쓰는 시계 (WebConfig와 나눠 순환 의존을 피한다) */
@Configuration
public class ClockConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
