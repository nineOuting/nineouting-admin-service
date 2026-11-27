package com.nineouting.backend.admin.actions;

import com.nineouting.backend.admin.audit.AuditLog;
import com.nineouting.backend.admin.db.DatabaseRegistry;
import com.nineouting.backend.admin.error.AdminException;
import com.nineouting.backend.admin.ops.ServiceCaller;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * REQ-218 관리자 빠른 작업. 표를 직접 고치지 않고 각 서비스의 내부 기능을 불러
 * 비밀번호 해시, 이메일 중복 확인, 멤버 중복 같은 평소 규칙이 그대로 적용되게 한다. 성공과 실패 모두 감사 기록에 남긴다.
 */
@Service
public class QuickActionService {

    private final ServiceCaller services;
    private final DatabaseRegistry databases;
    private final AuditLog audit;

    public QuickActionService(ServiceCaller services, DatabaseRegistry databases, AuditLog audit) {
        this.services = services;
        this.databases = databases;
        this.audit = audit;
    }

    public record CreatedUser(long userId, String email, String name) {
    }

    public record AddedMember(long workspaceId, long userId, boolean added) {
    }

    /** 이메일 인증 없이 시험 계정을 만든다. 비밀번호는 기록에 남기지 않는다 */
    public CreatedUser createUser(String email, String password, String name) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("email", email);
        detail.put("name", name);
        JsonNode data;
        try {
            data = services.post("user", "/internal/admin/users", body("email", email, "password", password, "name", name));
        } catch (AdminException e) {
            detail.put("code", e.code());
            audit.record("user.create", "user", false, detail);
            throw e;
        }
        long userId = data.path("userId").asLong();
        audit.record("user.create", "user#" + userId, true, detail);
        return new CreatedUser(userId, email, name);
    }

    /**
     * 사용자를 워크스페이스 일반 멤버로 넣는다. 사용자는 이메일이나 번호로 고른다 (이메일은 user DB에서 찾는다).
     * 이미 멤버면 바꾸지 않고 added=false
     */
    public AddedMember addWorkspaceMember(long workspaceId, String user) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("user", user);
        String target = "workspace#" + workspaceId;
        try {
            long userId = resolveUser(user);
            detail.put("userId", userId);
            JsonNode data = services.post("workspace", "/internal/admin/workspaces/{workspaceId}/members",
                    body("userId", userId), workspaceId);
            boolean added = data.path("added").asBoolean();
            detail.put("added", added);
            audit.record("workspace.addMember", target, true, detail);
            return new AddedMember(workspaceId, userId, added);
        } catch (AdminException e) {
            detail.put("code", e.code());
            audit.record("workspace.addMember", target, false, detail);
            throw e;
        }
    }

    private long resolveUser(String user) {
        String value = user == null ? "" : user.trim();
        if (value.isEmpty()) {
            throw AdminException.badRequest("사용자 이메일이나 번호를 입력해 주세요");
        }
        if (value.chars().allMatch(Character::isDigit)) {
            return Long.parseLong(value);
        }
        List<Long> ids = databases.require("user").jdbc()
                .queryForList("select id from users where lower(email) = lower(?)", Long.class, value);
        if (ids.isEmpty()) {
            throw new AdminException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "이 이메일로 가입한 사용자가 없습니다: " + value);
        }
        return ids.getFirst();
    }

    private static Map<String, Object> body(Object... pairs) {
        Map<String, Object> body = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            body.put((String) pairs[i], pairs[i + 1]);
        }
        return body;
    }
}
