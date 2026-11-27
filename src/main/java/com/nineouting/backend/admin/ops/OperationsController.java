package com.nineouting.backend.admin.ops;

import com.nineouting.backend.admin.ops.OperationsService.Operation;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** REQ-217 운영 조치 (멈춘 회의 마무리, 실패한 초안 다시 요청, 실패한 검색 반영 다시 보내기) */
@RestController
@RequestMapping("/api/admin/operations")
public class OperationsController {

    private final OperationsService operations;

    public OperationsController(OperationsService operations) {
        this.operations = operations;
    }

    @PostMapping("/meetings/{id}/finish")
    public ResponseEntity<Void> finishMeeting(@PathVariable long id) {
        return run(Operation.MEETING_FINISH, id);
    }

    @PostMapping("/materials/{id}/rag-retry")
    public ResponseEntity<Void> retryMaterialRag(@PathVariable long id) {
        return run(Operation.MATERIAL_RAG_RETRY, id);
    }

    @PostMapping("/minutes/{id}/draft-retry")
    public ResponseEntity<Void> retryDraft(@PathVariable long id) {
        return run(Operation.DRAFT_RETRY, id);
    }

    @PostMapping("/minutes/{id}/rag-retry")
    public ResponseEntity<Void> retryMinutesRag(@PathVariable long id) {
        return run(Operation.MINUTES_RAG_RETRY, id);
    }

    private ResponseEntity<Void> run(Operation operation, long id) {
        operations.run(operation, id);
        return ResponseEntity.noContent().build();
    }
}
