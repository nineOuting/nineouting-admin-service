package com.nineouting.backend.admin.access;

import com.nineouting.backend.admin.config.AdminProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * AWS가 공개하는 CloudFront IP 대역 (ip-ranges.json). 클라이언트 IP를 찾을 때 CloudFront를 건너뛰는 데 쓴다.
 * 12시간마다 다시 받는다. 받지 못하면 예전 목록을 그대로 쓴다.
 */
@Component
public class CloudFrontRanges {

    private static final Logger log = LoggerFactory.getLogger(CloudFrontRanges.class);
    private static final String SERVICE = "CLOUDFRONT";

    private final AdminProperties properties;
    private final JsonMapper jsonMapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private volatile List<Cidr> ranges = List.of();

    public CloudFrontRanges(AdminProperties properties, JsonMapper jsonMapper) {
        this.properties = properties;
        this.jsonMapper = jsonMapper;
    }

    public List<Cidr> ranges() {
        return ranges;
    }

    /** 허용 목록을 쓰는데 대역을 아직 못 받았으면 클라이언트 IP를 정할 수 없다 */
    public boolean loaded() {
        return !ranges.isEmpty();
    }

    @Scheduled(initialDelay = 0, fixedDelayString = "PT12H")
    public void refresh() {
        String url = properties.cloudfrontRangesUrl();
        if (url == null || url.isBlank()) {
            return;
        }
        try {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("CloudFront IP 대역을 받지 못했습니다 (HTTP {})", response.statusCode());
                return;
            }
            List<Cidr> parsed = parse(jsonMapper.readTree(response.body()));
            if (!parsed.isEmpty()) {
                ranges = List.copyOf(parsed);
                log.info("CloudFront IP 대역 {}개", parsed.size());
            }
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("CloudFront IP 대역을 받지 못했습니다: {}", e.getMessage());
        }
    }

    static List<Cidr> parse(JsonNode root) {
        List<Cidr> result = new ArrayList<>();
        root.path("prefixes").forEach(p -> {
            if (SERVICE.equals(p.path("service").asString(""))) {
                result.add(Cidr.parse(p.path("ip_prefix").asString()));
            }
        });
        root.path("ipv6_prefixes").forEach(p -> {
            if (SERVICE.equals(p.path("service").asString(""))) {
                result.add(Cidr.parse(p.path("ipv6_prefix").asString()));
            }
        });
        return result;
    }

    /** 시험용 */
    void replace(List<Cidr> next) {
        ranges = List.copyOf(next);
    }
}
