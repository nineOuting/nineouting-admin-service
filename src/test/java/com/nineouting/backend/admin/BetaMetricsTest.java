package com.nineouting.backend.admin;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nineouting.backend.admin.auth.AuthInterceptor;
import jakarta.servlet.http.Cookie;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** 베타 테스트 개발팀 집계. 서비스 DB 둘(meeting, minutes)을 PostgreSQL 하나로 대신하고 개수만 확인한다 */
@SpringBootTest
@AutoConfigureMockMvc
class BetaMetricsTest {

    private static final String USERNAME = "tester";
    private static final String PASSWORD = "test-password";
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.6-alpine");

    @Autowired
    MockMvc mockMvc;

    static {
        POSTGRES.start();
        execute("""
                create table meetings (id bigint primary key, workspace_id bigint, status varchar(20) not null,
                  start_at timestamp not null, deleted_at timestamp);
                create table agenda_states (meeting_id bigint primary key, today_summary text);
                create table chat_messages (id bigint generated always as identity primary key, meeting_id bigint,
                  role varchar(20), citations text);
                create table minutes (id bigint primary key, workspace_id bigint, meeting_start_at timestamp,
                  confirmed_at timestamp, draft_lines text, template_id bigint);
                create table minutes_revisions (id bigint generated always as identity primary key, minutes_id bigint,
                  version int, edited_at timestamp, snapshot text not null, after_confirm boolean not null);
                create table templates (id bigint primary key, owner_user_id bigint, workspace_id bigint,
                  personal_default boolean not null default false, created_at timestamp not null);
                create table template_items (id bigint generated always as identity primary key, template_id bigint,
                  position int, title varchar(100));
                create table template_imports (id bigint generated always as identity primary key, status varchar(20),
                  template_id bigint, created_at timestamp not null);

                -- 워크스페이스 100: 참가 팀, 200: 우리 팀(뺀다)
                insert into meetings values
                  (1, 100, 'FINISHED', '2026-12-02 10:00', null),
                  (2, 100, 'FINISHED', '2026-12-03 10:00', null),
                  (3, 200, 'FINISHED', '2026-12-03 10:00', null),
                  (4, 100, 'CANCELLED', '2026-12-03 10:00', null),
                  (5, 100, 'FINISHED', '2026-11-01 10:00', null);
                insert into agenda_states values (1, '{"agendas":[{"agendaId":"a1",
                  "summary":[{"id":"s1","edited":false},{"id":"s2","edited":true},{"id":"u-1","edited":true}],
                  "decisionPoints":[{"decisionPointId":"d1","status":"DECIDED","edited":true},{"decisionPointId":"d2","status":"UNDECIDED"}],
                  "todos":[{"id":"t1","assigneeName":"김","assigneeStatus":"MATCHED","edited":false},
                           {"id":"t2","assigneeName":null,"assigneeStatus":"UNASSIGNED","edited":true}]}]}');
                insert into chat_messages (meeting_id, role, citations) values
                  (1, 'USER', null), (1, 'USER', null),
                  (1, 'ASSISTANT', '[{"sourceType":"live"}]'),
                  (1, 'ASSISTANT', '[{"sourceType":"minutes"},{"sourceType":"upload"}]'),
                  (2, 'USER', null), (3, 'USER', null), (3, 'USER', null);

                insert into templates values
                  (1, null, null, false, '2026-01-01'),
                  (2, 7, 100, false, '2026-12-01'),
                  (3, 7, 100, false, '2026-12-01');
                insert into template_items (template_id, position, title) values
                  (1, 0, '논의'), (2, 0, '안건'), (2, 1, '할 일'), (3, 0, 'A');
                insert into template_imports (status, template_id, created_at) values
                  ('SUCCEEDED', 3, '2026-12-01'), ('FAILED', null, '2026-12-01'), ('SUCCEEDED', 99, '2026-12-02');
                insert into minutes values
                  (10, 100, '2026-12-02 10:00', '2026-12-02 12:00',
                   '[{"title":"안건","type":"text","sentence":"가"},{"title":"안건","type":"text","sentence":"나"},{"title":"할 일","type":"text","sentence":"다"},{"title":"메모","type":"text","sentence":"해당 내용 없음"}]', 2),
                  (11, 100, '2026-12-03 10:00', '2026-12-03 12:00', '[{"title":"A","type":"text","sentence":"x"}]', 3),
                  (12, 100, '2026-12-03 10:00', null, null, 3),
                  (13, 200, '2026-12-03 10:00', '2026-12-03 12:00', '[{"title":"A","type":"text","sentence":"x"}]', 3);
                insert into minutes_revisions (minutes_id, version, edited_at, snapshot, after_confirm) values
                  (10, 1, '2026-12-02 11:00', '[{"title":"안건","content":"- 가\\n- 나"},{"title":"할 일","content":"- 다 고침"},{"title":"메모","content":"- 작성자가 씀"},{"title":"새 칸","content":"- 추가"}]', false),
                  (10, 2, '2026-12-02 13:00', '[{"title":"안건","content":"- 가"},{"title":"할 일","content":"- 다"}]', true);
                """);
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("nineouting.admin.username", () -> USERNAME);
        registry.add("nineouting.admin.password-hash", () -> new BCryptPasswordEncoder().encode(PASSWORD));
        registry.add("nineouting.admin.session-secret", () -> "test-session-secret-0123456789-abcdef");
        registry.add("nineouting.admin.cookie-secure", () -> "false");
        registry.add("nineouting.admin.cloudfront-ranges-url", () -> "");
        for (String db : List.of("meeting", "minutes")) {
            registry.add("nineouting.admin.databases." + db + ".url", POSTGRES::getJdbcUrl);
            registry.add("nineouting.admin.databases." + db + ".username", POSTGRES::getUsername);
            registry.add("nineouting.admin.databases." + db + ".password", POSTGRES::getPassword);
        }
    }

    @Test
    void 기간과_뺄_워크스페이스로_개수만_센다() throws Exception {
        mockMvc.perform(get("/api/admin/beta-metrics")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/beta-metrics?from=2026-12-01&to=2026-12-31&exclude=200").cookie(loggedIn()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unavailable").isEmpty())
                // D1: 취소한 회의, 기간 밖 회의, 뺀 워크스페이스는 세지 않는다
                .andExpect(jsonPath("$.total.D1.meetings").value(2))
                .andExpect(jsonPath("$.total.D1.finishedMeetings").value(2))
                .andExpect(jsonPath("$.total.D1.completedMinutes").value(2))
                // D2: 처음 작성 완료한 때(완료 직전 저장 기록)와 비교. 저장 기록이 없으면 초안 그대로
                .andExpect(jsonPath("$.total.D2.minutesWithDraft").value(2))
                .andExpect(jsonPath("$.total.D2.draftSections").value(3))
                .andExpect(jsonPath("$.total.D2.unchangedSections").value(2))
                .andExpect(jsonPath("$.total.D2.contentChangedSections").value(1))
                .andExpect(jsonPath("$.total.D2.removedSections").value(0))
                // AI가 비운 칸(해당 내용 없음)을 채운 것과, 초안에 없던 칸을 넣은 것을 나눈다
                .andExpect(jsonPath("$.total.D2.filledEmptySections").value(1))
                .andExpect(jsonPath("$.total.D2.addedSections").value(1))
                // D3, D5, D6: 회의가 끝난 뒤의 오늘 회의 요약. u- 번호는 사람이 넣은 항목
                .andExpect(jsonPath("$.total.D3.summarySentences").value(2))
                .andExpect(jsonPath("$.total.D3.editedSummarySentences").value(1))
                .andExpect(jsonPath("$.total.D3.addedSummarySentences").value(1))
                .andExpect(jsonPath("$.total.D5.decisionPoints").value(2))
                .andExpect(jsonPath("$.total.D5.decidedPoints").value(1))
                .andExpect(jsonPath("$.total.D5.editedDecisionPoints").value(1))
                .andExpect(jsonPath("$.total.D6.todos").value(2))
                .andExpect(jsonPath("$.total.D6.todosWithAssignee").value(1))
                .andExpect(jsonPath("$.total.D6.assigneeStatus_UNASSIGNED").value(1))
                .andExpect(jsonPath("$.total.D6.editedTodos").value(1))
                // D7: 질문 수, 2개 이상인 회의, 답의 출처 종류
                .andExpect(jsonPath("$.total.D7.questions").value(3))
                .andExpect(jsonPath("$.total.D7.meetingsWithQuestion").value(2))
                .andExpect(jsonPath("$.total.D7.meetingsWithTwoOrMoreQuestions").value(1))
                .andExpect(jsonPath("$.total.D7.workspacesUsingChatbot").value(1))
                .andExpect(jsonPath("$.total.D7.answersFromLive").value(1))
                .andExpect(jsonPath("$.total.D7.answersFromPastMinutes").value(1))
                .andExpect(jsonPath("$.total.D7.answersFromMaterials").value(1))
                // D8: 직접 만든 양식과 그 양식을 쓴 회의록
                .andExpect(jsonPath("$.total.D8.customTemplates").value(1))
                .andExpect(jsonPath("$.total.D8.usedCustomTemplates").value(1))
                .andExpect(jsonPath("$.total.D8.minutesWithCustomTemplate").value(1))
                .andExpect(jsonPath("$.total.D8.minutesWithImportedTemplate").value(2))
                .andExpect(jsonPath("$.total.D8.minutesWithSectionChange_custom").value(0))
                // D9: 가져오기, 두 번 이상 쓰인 양식, 가져온 뒤 지운 양식
                .andExpect(jsonPath("$.total.D9.imports").value(3))
                .andExpect(jsonPath("$.total.D9.succeededImports").value(2))
                .andExpect(jsonPath("$.total.D9.failedImports").value(1))
                .andExpect(jsonPath("$.total.D9.importedTemplatesDeleted").value(1))
                .andExpect(jsonPath("$.total.D9.importedTemplatesUsedTwiceOrMore").value(1))
                // 워크스페이스별
                .andExpect(jsonPath("$.byWorkspace.100.D1.meetings").value(2))
                .andExpect(jsonPath("$.byWorkspace.200").doesNotExist());
    }

    @Test
    void 잘못된_기간은_막는다() throws Exception {
        Cookie cookie = loggedIn();
        mockMvc.perform(get("/api/admin/beta-metrics?from=2026-12-31&to=2026-12-01").cookie(cookie))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/admin/beta-metrics?from=12월").cookie(cookie)).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/admin/beta-metrics?exclude=a,b").cookie(cookie)).andExpect(status().isBadRequest());
    }

    private Cookie loggedIn() throws Exception {
        return mockMvc.perform(post("/api/admin/session")
                        .header(AuthInterceptor.REQUEST_HEADER, AuthInterceptor.REQUEST_HEADER_VALUE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + USERNAME + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie(AuthInterceptor.COOKIE);
    }

    private static void execute(String sql) {
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
