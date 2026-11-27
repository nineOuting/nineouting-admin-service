package com.nineouting.backend.admin.access;

import com.nineouting.backend.admin.config.AdminProperties;
import com.nineouting.backend.admin.error.AdminException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 허용 IP에서 온 요청만 받는다 (로그인 포함). 허용 목록이 비어 있으면 모두 받는다 (로컬, 시험).
 *
 * <p>클라이언트 IP: X-Forwarded-For 뒤에 직접 붙은 곳(ALB)을 더해 오른쪽부터 보며, 클러스터 안 주소와 CloudFront 대역은
 * 건너뛰고 처음 나오는 주소. CloudFront, ALB는 받은 목록 끝에 자기가 본 주소를 붙이므로, 누가 머리글을 꾸며 보내거나
 * ALB로 바로 붙어도 그 사람의 실제 주소가 잡힌다.
 */
@Component
public class IpAccessInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(IpAccessInterceptor.class);
    /** 클러스터, VPC 안 주소 (ALB, 파드) */
    private static final List<Cidr> INTERNAL = List.of(Cidr.parse("10.0.0.0/8"), Cidr.parse("172.16.0.0/12"),
            Cidr.parse("192.168.0.0/16"), Cidr.parse("127.0.0.0/8"), Cidr.parse("::1/128"), Cidr.parse("fc00::/7"));

    private final List<Cidr> allowed;
    private final CloudFrontRanges cloudFront;

    public IpAccessInterceptor(AdminProperties properties, CloudFrontRanges cloudFront) {
        this.allowed = properties.allowedIps().stream().map(Cidr::parse).toList();
        this.cloudFront = cloudFront;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (allowed.isEmpty()) {
            return true;
        }
        if (!cloudFront.loaded()) {
            throw new AdminException(HttpStatus.SERVICE_UNAVAILABLE, "IP_CHECK_UNAVAILABLE",
                    "접속 IP를 확인할 수 없습니다. 잠시 후 다시 시도해 주세요");
        }
        Optional<byte[]> client = clientAddress(request.getHeader("X-Forwarded-For"), request.getRemoteAddr(),
                cloudFront.ranges());
        if (client.isEmpty() || allowed.stream().noneMatch(cidr -> cidr.contains(client.get()))) {
            log.warn("허용되지 않은 IP의 관리자 요청 (forwarded={}, remote={})", request.getHeader("X-Forwarded-For"),
                    request.getRemoteAddr());
            throw new AdminException(HttpStatus.FORBIDDEN, "IP_NOT_ALLOWED", "허용되지 않은 곳에서 접속했습니다");
        }
        return true;
    }

    static Optional<byte[]> clientAddress(String forwardedFor, String remoteAddr, List<Cidr> cloudFront) {
        List<String> chain = new ArrayList<>();
        if (forwardedFor != null) {
            for (String part : forwardedFor.split(",")) {
                if (!part.isBlank()) {
                    chain.add(part.trim());
                }
            }
        }
        chain.add(remoteAddr);
        Collections.reverse(chain);
        for (String entry : chain) {
            byte[] address;
            try {
                address = Cidr.toBytes(entry);
            } catch (IllegalArgumentException e) {
                // 주소가 아닌 값이 끼어 있으면 그 뒤(더 왼쪽)는 믿지 않는다
                return Optional.empty();
            }
            if (INTERNAL.stream().anyMatch(c -> c.contains(address)) || cloudFront.stream().anyMatch(c -> c.contains(address))) {
                continue;
            }
            return Optional.of(address);
        }
        return Optional.empty();
    }
}
