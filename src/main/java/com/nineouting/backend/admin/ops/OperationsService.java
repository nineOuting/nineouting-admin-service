package com.nineouting.backend.admin.ops;

import com.nineouting.backend.admin.audit.AuditLog;
import com.nineouting.backend.admin.error.AdminException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

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

    private final ServiceCaller services;
    private final AuditLog audit;

    public OperationsService(ServiceCaller services, AuditLog audit) {
        this.services = services;
        this.audit = audit;
    }

    public void run(Operation operation, long id) {
        // 기록에는 대상 종류와 번호 (meeting#12, material#3, minutes#7)
        String target = operation.action.substring(0, operation.action.indexOf('.')) + "#" + id;
        try {
            services.post(operation.service, operation.path, null, id);
        } catch (AdminException e) {
            audit.record(operation.action, target, false, Map.of("code", e.code()));
            if (e.status() == HttpStatus.NOT_FOUND) {
                throw new AdminException(HttpStatus.NOT_FOUND, e.code(), "대상을 찾을 수 없거나 조치할 수 있는 상태가 아닙니다");
            }
            throw e;
        }
        audit.record(operation.action, target, true, null);
    }
}
