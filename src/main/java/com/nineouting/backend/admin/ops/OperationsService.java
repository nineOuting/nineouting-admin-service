package com.nineouting.backend.admin.ops;

import com.nineouting.backend.admin.audit.AuditLog;
import com.nineouting.backend.admin.config.AdminProperties;
import com.nineouting.backend.admin.error.AdminException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * REQ-217 운영 조치. 표를 직접 고치지 않고 각 서비스의 내부 기능(/internal/admin/...)을 부른다.
 * 그래야 평소 마무리, 다시 요청과 같은 후속 처리(회의록 만들기, 회의방 닫기, 캐시)가 따라온다.
 * 서비스가 준 오류 코드와 문구를 그대로 화면에 보여 주고, 성공과 실패 모두 감사 기록에 남긴다.
 */
@Service
public class OperationsService {

    public enum Operation {
        MEETING_FINISH("meeting", "/internal/admin/meetings/{id}/finish", "meeting.finish"),
        MATERIAL_RAG_RETRY("meeting", "/internal/admin/materials/{id}/rag-retry", "material.ragRetry"),
        DRAFT_RETRY("minutes", "/internal/admin/minutes/{id}/draft-retry", "minutes.draftRetry"),
        MINUTES_RAG_RETRY("minutes", "/internal/admin/minutes/{id}/rag-retry", "minutes.ragRetry");

        final String service;
        final String path;
        final String action;

        Operation(String service, String path, String action) {
            this.service = service;
            this.path = path;
            this.action = action;
        }
    }

    private final Map<String, RestClient> clients = new LinkedHashMap<>();
    private final AuditLog audit;
    private final JsonMapper json;

    public OperationsService(AdminProperties properties, AuditLog audit, JsonMapper json) {
        this.audit = audit;
        this.json = json;
        put("meeting", properties.services().meetingUrl());
        put("minutes", properties.services().minutesUrl());
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

    public void run(Operation operation, long id) {
        RestClient client = clients.get(operation.service);
        // 기록에는 대상 종류와 번호 (meeting#12, material#3, minutes#7)
        String target = operation.action.substring(0, operation.action.indexOf('.')) + "#" + id;
        if (client == null) {
            throw new AdminException(HttpStatus.SERVICE_UNAVAILABLE, "OPERATION_UNAVAILABLE",
                    operation.service + "-service 주소가 설정되지 않았습니다");
        }
        try {
            client.post().uri(operation.path, id).retrieve().toBodilessEntity();
        } catch (RestClientResponseException e) {
            AdminException error = fromService(e.getStatusCode(), e.getResponseBodyAsString());
            audit.record(operation.action, target, false, Map.of("code", error.code()));
            throw error;
        } catch (ResourceAccessException e) {
            audit.record(operation.action, target, false, Map.of("code", "SERVICE_UNREACHABLE"));
            throw new AdminException(HttpStatus.BAD_GATEWAY, "SERVICE_UNREACHABLE",
                    operation.service + "-service에 연결하지 못했습니다");
        }
        audit.record(operation.action, target, true, null);
    }

    /** 서비스의 {"code", "message"}를 그대로. 읽지 못하면 상태 코드만 */
    private AdminException fromService(HttpStatusCode status, String body) {
        String code = "SERVICE_ERROR";
        String message = "서비스가 요청을 처리하지 못했습니다 (" + status.value() + ")";
        try {
            JsonNode node = json.readTree(body);
            code = node.path("code").asString(code);
            message = node.path("message").asString(message);
        } catch (RuntimeException ignored) {
            // 본문이 JSON이 아니면 기본 문구
        }
        if (status.value() == HttpStatus.NOT_FOUND.value()) {
            message = "대상을 찾을 수 없거나 조치할 수 있는 상태가 아닙니다";
        }
        HttpStatus mapped = status.is4xxClientError() ? HttpStatus.valueOf(status.value()) : HttpStatus.BAD_GATEWAY;
        return new AdminException(mapped, code, message);
    }
}
