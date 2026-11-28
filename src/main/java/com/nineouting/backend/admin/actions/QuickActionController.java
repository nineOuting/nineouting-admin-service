package com.nineouting.backend.admin.actions;

import com.nineouting.backend.admin.actions.QuickActionService.AddedMember;
import com.nineouting.backend.admin.actions.QuickActionService.CreatedUser;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** REQ-218 관리자 빠른 작업 */
@RestController
@RequestMapping("/api/admin/actions")
public class QuickActionController {

    private final QuickActionService actions;

    public QuickActionController(QuickActionService actions) {
        this.actions = actions;
    }

    public record CreateUserRequest(String email, String password, String name) {
    }

    /** @param user 이메일이나 사용자 번호 */
    public record AddMemberRequest(long workspaceId, String user) {
    }

    @PostMapping("/users")
    public ResponseEntity<CreatedUser> createUser(@RequestBody CreateUserRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(actions.createUser(request.email(), request.password(), request.name()));
    }

    @PostMapping("/workspace-members")
    public AddedMember addWorkspaceMember(@RequestBody AddMemberRequest request) {
        return actions.addWorkspaceMember(request.workspaceId(), request.user());
    }
}
