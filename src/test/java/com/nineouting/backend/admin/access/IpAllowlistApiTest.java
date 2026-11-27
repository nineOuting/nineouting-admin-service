package com.nineouting.backend.admin.access;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** 허용 IP가 아니면 로그인 전부터 막는다. CloudFront 대역을 모르면 판단하지 않고 막는다 */
@SpringBootTest(properties = {
        "nineouting.admin.username=tester",
        "nineouting.admin.password-hash=$2a$10$7EqJtq98hPqEX7fNZaFWoOhi5BWX4Z2rFVrrz2e0VgaCxKzwHXxHe",
        "nineouting.admin.session-secret=test-session-secret-0123456789-abcdef",
        "nineouting.admin.allowed-ips=203.0.113.0/24, 2001:db8::/32",
        "nineouting.admin.cloudfront-ranges-url="})
@AutoConfigureMockMvc
class IpAllowlistApiTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    CloudFrontRanges cloudFront;

    @BeforeEach
    void cloudFrontRanges() {
        cloudFront.replace(List.of(Cidr.parse("130.176.0.0/16")));
    }

    @AfterEach
    void reset() {
        cloudFront.replace(List.of());
    }

    @Test
    void 허용_IP는_통과해_로그인_확인으로_간다() throws Exception {
        mockMvc.perform(from(get("/api/admin/session"), "203.0.113.5, 130.176.10.1", "10.20.0.15"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(from(get("/api/admin/session"), null, "203.0.113.9"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 허용되지_않은_IP와_꾸민_머리글은_막는다() throws Exception {
        mockMvc.perform(from(get("/api/admin/session"), "198.51.100.7, 130.176.10.1", "10.20.0.15"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("IP_NOT_ALLOWED"));
        mockMvc.perform(from(get("/api/admin/session"), "203.0.113.5", "198.51.100.7"))
                .andExpect(status().isForbidden());
    }

    @Test
    void CloudFront_대역을_모르면_막는다() throws Exception {
        cloudFront.replace(List.of());
        mockMvc.perform(from(get("/api/admin/session"), "203.0.113.5, 130.176.10.1", "10.20.0.15"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("IP_CHECK_UNAVAILABLE"));
    }

    @Test
    void 상태_확인_주소는_막지_않는다() throws Exception {
        mockMvc.perform(from(get("/actuator/health"), null, "198.51.100.7")).andExpect(status().isOk());
    }

    private static MockHttpServletRequestBuilder from(MockHttpServletRequestBuilder request, String forwarded, String remote) {
        if (forwarded != null) {
            request.header("X-Forwarded-For", forwarded);
        }
        return request.with(r -> {
            r.setRemoteAddr(remote);
            return r;
        });
    }
}
