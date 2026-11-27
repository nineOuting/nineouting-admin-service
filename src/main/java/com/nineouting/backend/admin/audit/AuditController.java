package com.nineouting.backend.admin.audit;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** REQ-216 감사 기록 보기 (최신순). 고치거나 지우는 주소는 두지 않는다 */
@RestController
@RequestMapping("/api/admin/audit")
public class AuditController {

    private final AuditLog audit;

    public AuditController(AuditLog audit) {
        this.audit = audit;
    }

    @GetMapping
    public AuditLog.Page page(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size,
                              @RequestParam(required = false) String action) {
        return audit.page(page, size, action);
    }
}
