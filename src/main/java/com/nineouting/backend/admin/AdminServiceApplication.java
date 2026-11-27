package com.nineouting.backend.admin;

import com.nineouting.backend.admin.config.AdminProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 관리자 페이지 백엔드 (REQ-214). 서비스 DB 5개의 표를 보고 한 행씩 추가, 수정, 삭제한다.
 */
@SpringBootApplication
@EnableConfigurationProperties(AdminProperties.class)
@EnableScheduling
public class AdminServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AdminServiceApplication.class, args);
    }
}
