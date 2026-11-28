package com.nineouting.backend.admin.ops;

import com.nineouting.backend.admin.config.AdminProperties;
import com.nineouting.backend.admin.error.AdminException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 각 서비스의 관리자용 내부 기능(/internal/admin/...)을 부른다 (REQ-217 운영 조치, REQ-218 빠른 작업).
 * 서비스가 준 오류 코드와 문구를 그대로 AdminException으로 바꾼다. 입력 검사 오류는 칸별 문구를 이어 붙인다.
 */
@Component
public class ServiceCaller {

    private final Map<String, RestClient> clients = new LinkedHashMap<>();
    private final JsonMapper json;

    public ServiceCaller(AdminProperties properties, JsonMapper json) {
        this.json = json;
        put("meeting", properties.services().meetingUrl());
        put("minutes", properties.services().minutesUrl());
        put("user", properties.services().userUrl());
        put("workspace", properties.services().workspaceUrl());
    }

    private void put(String name, String url) {
        if (url == null || url.isBlank()) {
            return;
        }
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(15));
        clients.put(name, RestClient.builder().baseUrl(url).requestFactory(factory).build());
    }

    /**
     * POST 하고 응답의 data를 돌려준다 (본문이 없으면 비어 있는 노드).
     * @param body null이면 본문 없이 보낸다
     */
    public JsonNode post(String service, String path, Object body, Object... uriVariables) {
        RestClient client = clients.get(service);
        if (client == null) {
            throw new AdminException(HttpStatus.SERVICE_UNAVAILABLE, "OPERATION_UNAVAILABLE",
                    service + "-service 주소가 설정되지 않았습니다");
        }
        try {
            RestClient.RequestBodySpec request = client.post().uri(path, uriVariables);
            if (body != null) {
                request.contentType(MediaType.APPLICATION_JSON).body(body);
            }
            String response = request.retrieve().body(String.class);
            return response == null || response.isBlank() ? json.createObjectNode() : json.readTree(response).path("data");
        } catch (RestClientResponseException e) {
            throw fromService(e.getStatusCode(), e.getResponseBodyAsString());
        } catch (ResourceAccessException e) {
            throw new AdminException(HttpStatus.BAD_GATEWAY, "SERVICE_UNREACHABLE", service + "-service에 연결하지 못했습니다");
        }
    }

    /** 서비스의 {"code", "message", "data"}를 그대로. 읽지 못하면 상태 코드만 */
    private AdminException fromService(HttpStatusCode status, String body) {
        String code = "SERVICE_ERROR";
        String message = "서비스가 요청을 처리하지 못했습니다 (" + status.value() + ")";
        try {
            JsonNode node = json.readTree(body);
            code = node.path("code").asString(code);
            message = node.path("message").asString(message);
            // 입력 검사 오류: [{"field", "reason"}] (common GlobalExceptionHandler의 FieldErrorDetail)
            List<String> fields = new ArrayList<>();
            node.path("data").forEach(item -> {
                if (item.hasNonNull("reason")) {
                    fields.add(item.path("reason").asString());
                }
            });
            if (!fields.isEmpty()) {
                message = String.join(", ", fields);
            }
        } catch (RuntimeException ignored) {
            // 본문이 JSON이 아니면 기본 문구
        }
        HttpStatus mapped = status.is4xxClientError() ? HttpStatus.valueOf(status.value()) : HttpStatus.BAD_GATEWAY;
        return new AdminException(mapped, code, message);
    }
}
