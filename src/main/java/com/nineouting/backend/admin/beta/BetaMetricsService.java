package com.nineouting.backend.admin.beta;

import com.nineouting.backend.admin.db.DatabaseRegistry;
import com.nineouting.backend.admin.db.DatabaseRegistry.Target;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 넵넵 베타 테스트 설계서 5장의 개발팀 집계 (docs/beta-test-dev-check.md). 서비스 DB에서 읽기만 한다.
 * 글(회의록, 요약, 질문)은 서버 안에서 비교에만 쓰고 밖으로는 개수만 낸다.
 * 기간은 회의 시작 날짜(한국 시간, 양식은 만든 날짜) 기준이고, 뺄 워크스페이스(우리 팀 내부 테스트)를 받는다.
 * 지금 기록으로 셀 수 없는 것(D4 근거를 연 카드, D5 되돌리기 10초 기준, D9 가져온 뒤 고친 섹션 수)은 넣지 않는다.
 */
@Service
public class BetaMetricsService {

    private static final Logger log = LoggerFactory.getLogger(BetaMetricsService.class);
    /** 점검에서 사람이 새로 넣은 항목 번호 (meeting-service TodaySummaryRevision) */
    private static final String USER_ITEM_PREFIX = "u-";
    /** AI가 채울 내용이 없는 칸의 글 (minutes-service DraftLines) */
    private static final String NOTHING = "해당 내용 없음";

    private final DatabaseRegistry registry;
    private final JsonMapper json;

    public BetaMetricsService(DatabaseRegistry registry, JsonMapper json) {
        this.registry = registry;
        this.json = json;
    }

    /** 집계 조건. to는 그날까지 포함 */
    public record Query(LocalDate from, LocalDate to, Set<Long> excludedWorkspaces) {
    }

    /**
     * @param total       전체 개수 (D번호 → 이름 → 개수)
     * @param byWorkspace 워크스페이스별 같은 개수
     * @param unavailable 읽지 못한 DB
     */
    public record Report(Query query, Map<String, Map<String, Long>> total,
                         Map<Long, Map<String, Map<String, Long>>> byWorkspace, List<String> unavailable) {
    }

    /** 개수를 모으는 곳: 전체와 워크스페이스별에 같이 더한다 */
    private static final class Counter {
        final Map<String, Map<String, Long>> total = new LinkedHashMap<>();
        final Map<Long, Map<String, Map<String, Long>>> byWorkspace = new TreeMap<>();

        void add(Long workspaceId, String group, String name, long value) {
            total.computeIfAbsent(group, g -> new LinkedHashMap<>()).merge(name, value, Long::sum);
            if (workspaceId != null) {
                byWorkspace.computeIfAbsent(workspaceId, w -> new LinkedHashMap<>())
                        .computeIfAbsent(group, g -> new LinkedHashMap<>()).merge(name, value, Long::sum);
            }
        }

        /** 0이어도 이름이 보이게 */
        void declare(String group, String... names) {
            Map<String, Long> map = total.computeIfAbsent(group, g -> new LinkedHashMap<>());
            for (String name : names) {
                map.putIfAbsent(name, 0L);
            }
        }
    }

    public Report load(Query query) {
        Counter counter = new Counter();
        List<String> unavailable = new ArrayList<>();
        read("meeting", unavailable, jdbc -> {
            meetings(jdbc, query, counter);
            todaySummaries(jdbc, query, counter);
            chatbot(jdbc, query, counter);
            return true;
        });
        read("minutes", unavailable, jdbc -> {
            completedMinutes(jdbc, query, counter);
            drafts(jdbc, query, counter);
            templates(jdbc, query, counter);
            return true;
        });
        return new Report(query, counter.total, counter.byWorkspace, unavailable);
    }

    // ---- D1 회의 수, 작성 완료 수 ----

    private void meetings(JdbcTemplate jdbc, Query q, Counter c) {
        c.declare("D1", "meetings", "finishedMeetings");
        jdbc.query("select workspace_id, status, count(*) from meetings where deleted_at is null and status <> 'CANCELLED'"
                        + " and start_at >= ? and start_at < ? group by workspace_id, status",
                rs -> {
                    long ws = rs.getLong(1);
                    if (q.excludedWorkspaces().contains(ws)) {
                        return;
                    }
                    long n = rs.getLong(3);
                    c.add(ws, "D1", "meetings", n);
                    if ("FINISHED".equals(rs.getString(2))) {
                        c.add(ws, "D1", "finishedMeetings", n);
                    }
                }, start(q), end(q));
    }

    private void completedMinutes(JdbcTemplate jdbc, Query q, Counter c) {
        c.declare("D1", "completedMinutes");
        jdbc.query("select workspace_id, count(*) from minutes where confirmed_at is not null"
                        + " and meeting_start_at >= ? and meeting_start_at < ? group by workspace_id",
                rs -> {
                    long ws = rs.getLong(1);
                    if (!q.excludedWorkspaces().contains(ws)) {
                        c.add(ws, "D1", "completedMinutes", rs.getLong(2));
                    }
                }, start(q), end(q));
    }

    // ---- D2 AI 초안: 처음 작성 완료한 때와 비교 ----

    /**
     * 섹션은 AI가 글을 채운 칸(초안 줄이 있는 제목)만 센다. 처음 작성 완료한 때의 내용은 완료 직전의 마지막 저장 기록이고,
     * 저장 기록이 없으면 초안 그대로다. 줄 모음이 같으면 그대로, 다르면 내용 수정. 칸이 없어졌으면 양식 변경(삭제)으로,
     * 초안에 없던 칸이 생겼으면 양식 변경(추가)으로 센다. 표 칸은 줄 비교가 맞지 않아 따로 센다.
     */
    private void drafts(JdbcTemplate jdbc, Query q, Counter c) {
        c.declare("D2", "minutesWithDraft", "draftSections", "unchangedSections", "contentChangedSections",
                "removedSections", "addedSections", "tableSectionsNotCompared");
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select m.id, m.workspace_id, m.draft_lines,
                       (select r.snapshot from minutes_revisions r
                        where r.minutes_id = m.id and r.after_confirm = false and r.edited_at <= m.confirmed_at
                        order by r.version desc limit 1) as snapshot
                from minutes m
                where m.confirmed_at is not null and m.draft_lines is not null
                  and m.meeting_start_at >= ? and m.meeting_start_at < ?""", start(q), end(q));
        for (Map<String, Object> row : rows) {
            long ws = ((Number) row.get("workspace_id")).longValue();
            if (q.excludedWorkspaces().contains(ws)) {
                continue;
            }
            Map<String, DraftSection> draft = draftSections((String) row.get("draft_lines"));
            if (draft.isEmpty()) {
                continue;
            }
            c.add(ws, "D2", "minutesWithDraft", 1);
            Map<String, String> finalSections = snapshotSections((String) row.get("snapshot"));
            for (Map.Entry<String, DraftSection> entry : draft.entrySet()) {
                DraftSection section = entry.getValue();
                c.add(ws, "D2", "draftSections", 1);
                if (section.table()) {
                    c.add(ws, "D2", "tableSectionsNotCompared", 1);
                    continue;
                }
                if (finalSections != null && !finalSections.containsKey(entry.getKey())) {
                    c.add(ws, "D2", "removedSections", 1);
                    continue;
                }
                boolean same = finalSections == null
                        || lines(finalSections.get(entry.getKey()), section.headings()).equals(section.sentences());
                c.add(ws, "D2", same ? "unchangedSections" : "contentChangedSections", 1);
            }
            if (finalSections != null) {
                for (Map.Entry<String, String> entry : finalSections.entrySet()) {
                    String content = entry.getValue() == null ? "" : entry.getValue().strip();
                    if (!draft.containsKey(entry.getKey()) && !content.isEmpty()) {
                        c.add(ws, "D2", "addedSections", 1);
                    }
                }
            }
        }
    }

    record DraftSection(Set<String> sentences, Set<String> headings, boolean table) {
    }

    private Map<String, DraftSection> draftSections(String draftLines) {
        Map<String, DraftSection> sections = new LinkedHashMap<>();
        for (JsonNode line : parse(draftLines)) {
            String title = line.path("title").asString("");
            if (title.isEmpty()) {
                continue;
            }
            DraftSection section = sections.computeIfAbsent(title,
                    t -> new DraftSection(new LinkedHashSet<>(), new HashSet<>(), false));
            if ("table".equals(line.path("type").asString(""))) {
                sections.put(title, new DraftSection(section.sentences(), section.headings(), true));
                continue;
            }
            String sentence = line.path("sentence").asString("").strip();
            if (!sentence.isEmpty() && !NOTHING.equals(sentence)) {
                section.sentences().add(sentence);
            }
            String heading = line.path("heading").asString("").strip();
            if (!heading.isEmpty()) {
                section.headings().add(heading);
            }
        }
        // 채울 내용이 없던 칸(글 줄이 없음)은 AI가 채운 칸이 아니다
        sections.values().removeIf(s -> !s.table() && s.sentences().isEmpty());
        return sections;
    }

    /** 저장 기록 [{title, content}] → 제목별 글. 기록이 없으면 null (초안 그대로) */
    private Map<String, String> snapshotSections(String snapshot) {
        if (snapshot == null) {
            return null;
        }
        Map<String, String> sections = new LinkedHashMap<>();
        for (JsonNode section : parse(snapshot)) {
            sections.put(section.path("title").asString(""), section.path("content").asString(""));
        }
        return sections;
    }

    /** 칸의 글 → 줄 모음 (글머리표, 들여쓰기, 묶음 제목 줄, "해당 내용 없음"을 뺀다) */
    static Set<String> lines(String content, Set<String> headings) {
        Set<String> result = new LinkedHashSet<>();
        if (content == null) {
            return result;
        }
        for (String raw : content.split("\n")) {
            String line = raw.strip().replaceFirst("^[-*•]\\s*", "").strip();
            if (!line.isEmpty() && !NOTHING.equals(line) && !headings.contains(line)) {
                result.add(line);
            }
        }
        return result;
    }

    // ---- D3 요약, D5 결정(일부), D6 담당자(일부): 회의가 끝난 뒤의 오늘 회의 요약 ----

    private void todaySummaries(JdbcTemplate jdbc, Query q, Counter c) {
        c.declare("D3", "meetingsWithSummary", "summarySentences", "editedSummarySentences", "addedSummarySentences");
        c.declare("D5", "decisionPoints", "decidedPoints", "undecidedPoints", "otherStatusPoints", "editedDecisionPoints");
        c.declare("D6", "todos", "todosWithAssignee", "todosWithoutAssignee", "editedTodos", "addedTodos");
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select m.workspace_id, s.today_summary
                from meetings m join agenda_states s on s.meeting_id = m.id
                where m.deleted_at is null and m.status = 'FINISHED' and s.today_summary is not null
                  and m.start_at >= ? and m.start_at < ?""", start(q), end(q));
        for (Map<String, Object> row : rows) {
            long ws = ((Number) row.get("workspace_id")).longValue();
            if (q.excludedWorkspaces().contains(ws)) {
                continue;
            }
            c.add(ws, "D3", "meetingsWithSummary", 1);
            for (JsonNode agenda : parse((String) row.get("today_summary")).path("agendas")) {
                for (JsonNode item : agenda.path("summary")) {
                    if (isUserItem(item)) {
                        c.add(ws, "D3", "addedSummarySentences", 1);
                        continue;
                    }
                    c.add(ws, "D3", "summarySentences", 1);
                    if (item.path("edited").asBoolean(false)) {
                        c.add(ws, "D3", "editedSummarySentences", 1);
                    }
                }
                for (JsonNode point : agenda.path("decisionPoints")) {
                    c.add(ws, "D5", "decisionPoints", 1);
                    String status = point.path("status").asString("");
                    c.add(ws, "D5", switch (status) {
                        case "DECIDED" -> "decidedPoints";
                        case "UNDECIDED" -> "undecidedPoints";
                        default -> "otherStatusPoints";
                    }, 1);
                    if (point.path("edited").asBoolean(false)) {
                        c.add(ws, "D5", "editedDecisionPoints", 1);
                    }
                }
                for (JsonNode todo : agenda.path("todos")) {
                    if (isUserItem(todo)) {
                        c.add(ws, "D6", "addedTodos", 1);
                        continue;
                    }
                    c.add(ws, "D6", "todos", 1);
                    boolean assigned = !todo.path("assigneeName").asString("").isBlank()
                            || !todo.path("assigneeUserId").isNull() && !todo.path("assigneeUserId").isMissingNode();
                    c.add(ws, "D6", assigned ? "todosWithAssignee" : "todosWithoutAssignee", 1);
                    String assigneeStatus = todo.path("assigneeStatus").asString("");
                    if (!assigneeStatus.isEmpty()) {
                        c.add(ws, "D6", "assigneeStatus_" + assigneeStatus, 1);
                    }
                    if (todo.path("edited").asBoolean(false)) {
                        c.add(ws, "D6", "editedTodos", 1);
                    }
                }
            }
        }
    }

    private static boolean isUserItem(JsonNode item) {
        return item.path("id").asString("").startsWith(USER_ITEM_PREFIX);
    }

    // ---- D7 넵봇 ----

    private void chatbot(JdbcTemplate jdbc, Query q, Counter c) {
        c.declare("D7", "questions", "meetingsWithQuestion", "meetingsWithTwoOrMoreQuestions", "workspacesUsingChatbot",
                "answersFromLive", "answersFromPastMinutes", "answersFromMaterials", "answersWithoutSource");
        Map<Long, Long> questionsByMeeting = new HashMap<>();
        Map<Long, Long> workspaceOf = new HashMap<>();
        jdbc.query("""
                select m.id, m.workspace_id, count(*)
                from chat_messages c join meetings m on m.id = c.meeting_id
                where c.role = 'USER' and m.deleted_at is null and m.start_at >= ? and m.start_at < ?
                group by m.id, m.workspace_id""", rs -> {
            long ws = rs.getLong(2);
            if (!q.excludedWorkspaces().contains(ws)) {
                questionsByMeeting.put(rs.getLong(1), rs.getLong(3));
                workspaceOf.put(rs.getLong(1), ws);
            }
        }, start(q), end(q));
        Set<Long> workspaces = new HashSet<>();
        questionsByMeeting.forEach((meetingId, n) -> {
            long ws = workspaceOf.get(meetingId);
            c.add(ws, "D7", "questions", n);
            c.add(ws, "D7", "meetingsWithQuestion", 1);
            if (n >= 2) {
                c.add(ws, "D7", "meetingsWithTwoOrMoreQuestions", 1);
            }
            if (workspaces.add(ws)) {
                c.add(ws, "D7", "workspacesUsingChatbot", 1);
            }
        });
        jdbc.query("""
                select m.workspace_id, c.citations
                from chat_messages c join meetings m on m.id = c.meeting_id
                where c.role = 'ASSISTANT' and m.deleted_at is null and m.start_at >= ? and m.start_at < ?""", rs -> {
            long ws = rs.getLong(1);
            if (q.excludedWorkspaces().contains(ws)) {
                return;
            }
            Set<String> types = new HashSet<>();
            for (JsonNode citation : parse(rs.getString(2))) {
                types.add(citation.path("sourceType").asString(""));
            }
            if (types.contains("live")) {
                c.add(ws, "D7", "answersFromLive", 1);
            }
            if (types.contains("minutes")) {
                c.add(ws, "D7", "answersFromPastMinutes", 1);
            }
            if (types.contains("upload")) {
                c.add(ws, "D7", "answersFromMaterials", 1);
            }
            if (types.isEmpty()) {
                c.add(ws, "D7", "answersWithoutSource", 1);
            }
        }, start(q), end(q));
    }

    // ---- D8 양식 직접 만들기, D9 양식 가져오기(일부) ----

    /**
     * 양식 종류: 기본(주인 없음), 내 기본 양식(기본을 고친 것), 가져온 양식(가져오기로 만든 것), 직접 만든 양식(그 밖).
     * 섹션 추가, 삭제는 회의록의 마지막 저장 기록의 칸 제목과 양식 항목 제목이 다른 회의록 수로 센다.
     */
    private void templates(JdbcTemplate jdbc, Query q, Counter c) {
        c.declare("D8", "customTemplates", "usedCustomTemplates", "minutesWithCustomTemplate",
                "minutesWithDefaultTemplate", "minutesWithImportedTemplate", "minutesWithSectionChange_custom",
                "minutesWithSectionChange_default", "minutesWithSectionChange_imported");
        c.declare("D9", "imports", "succeededImports", "failedImports", "importedTemplatesUsed",
                "importedTemplatesUsedTwiceOrMore", "importedTemplatesDeleted");

        // 가져오기 기록 (기간: 가져온 날)
        Map<Long, Long> importedTemplateWorkspace = new HashMap<>();
        Set<Long> importedTemplates = new HashSet<>();
        jdbc.query("""
                select i.status, i.template_id, t.id, t.workspace_id
                from template_imports i left join templates t on t.id = i.template_id
                where i.created_at >= ? and i.created_at < ?""", rs -> {
            Long ws = (Long) rs.getObject(4);
            if (ws != null && q.excludedWorkspaces().contains(ws)) {
                return;
            }
            c.add(ws, "D9", "imports", 1);
            String status = rs.getString(1);
            if ("SUCCEEDED".equals(status)) {
                c.add(ws, "D9", "succeededImports", 1);
                Long templateId = (Long) rs.getObject(2);
                if (rs.getObject(3) == null) {
                    c.add(ws, "D9", "importedTemplatesDeleted", 1);
                } else {
                    importedTemplates.add(templateId);
                    importedTemplateWorkspace.put(templateId, ws);
                }
            } else if ("FAILED".equals(status)) {
                c.add(ws, "D9", "failedImports", 1);
            }
        }, start(q), end(q));
        // 기간 밖에 가져온 양식도 종류를 맞게 나누려고 전체 가져오기 양식 번호
        Set<Long> allImported = new HashSet<>(jdbc.queryForList(
                "select template_id from template_imports where template_id is not null", Long.class));

        Map<Long, String> kindOf = new HashMap<>();
        Map<Long, Long> templateWorkspace = new HashMap<>();
        Set<Long> customInPeriod = new HashSet<>();
        jdbc.query("select id, owner_user_id, workspace_id, personal_default, created_at from templates", rs -> {
            long id = rs.getLong(1);
            Object owner = rs.getObject(2);
            String kind = owner == null && rs.getObject(3) == null ? "default"
                    : rs.getBoolean(4) ? "default"
                    : allImported.contains(id) ? "imported" : "custom";
            kindOf.put(id, kind);
            Long ws = (Long) rs.getObject(3);
            if (ws != null) {
                templateWorkspace.put(id, ws);
            }
            Timestamp created = rs.getTimestamp(5);
            boolean inPeriod = !created.toLocalDateTime().isBefore(start(q).toLocalDateTime())
                    && created.toLocalDateTime().isBefore(end(q).toLocalDateTime());
            if ("custom".equals(kind) && inPeriod && (ws == null || !q.excludedWorkspaces().contains(ws))) {
                c.add(ws, "D8", "customTemplates", 1);
                customInPeriod.add(id);
            }
        });

        Map<Long, List<String>> itemTitles = new HashMap<>();
        jdbc.query("select template_id, title from template_items order by template_id, position",
                rs -> {
                    itemTitles.computeIfAbsent(rs.getLong(1), id -> new ArrayList<>()).add(rs.getString(2));
                });

        Map<Long, Long> usage = new HashMap<>();
        Set<Long> usedCustom = new HashSet<>();
        List<Map<String, Object>> minutesRows = jdbc.queryForList("""
                select m.workspace_id, m.template_id,
                       (select r.snapshot from minutes_revisions r where r.minutes_id = m.id
                        order by r.version desc limit 1) as snapshot
                from minutes m
                where m.template_id is not null and m.meeting_start_at >= ? and m.meeting_start_at < ?""",
                start(q), end(q));
        for (Map<String, Object> row : minutesRows) {
            long ws = ((Number) row.get("workspace_id")).longValue();
            if (q.excludedWorkspaces().contains(ws)) {
                continue;
            }
            long templateId = ((Number) row.get("template_id")).longValue();
            String kind = kindOf.getOrDefault(templateId, "default");
            c.add(ws, "D8", "minutesWith" + capitalize(kind) + "Template", 1);
            usage.merge(templateId, 1L, Long::sum);
            if (customInPeriod.contains(templateId)) {
                usedCustom.add(templateId);
            }
            Map<String, String> sections = snapshotSections((String) row.get("snapshot"));
            List<String> items = itemTitles.get(templateId);
            if (sections != null && items != null && !new ArrayList<>(sections.keySet()).equals(items)) {
                c.add(ws, "D8", "minutesWithSectionChange_" + kind, 1);
            }
        }
        for (Long templateId : usedCustom) {
            c.add(templateWorkspace.get(templateId), "D8", "usedCustomTemplates", 1);
        }
        for (Long templateId : importedTemplates) {
            long n = usage.getOrDefault(templateId, 0L);
            Long ws = importedTemplateWorkspace.get(templateId);
            if (n >= 1) {
                c.add(ws, "D9", "importedTemplatesUsed", 1);
            }
            if (n >= 2) {
                c.add(ws, "D9", "importedTemplatesUsedTwiceOrMore", 1);
            }
        }
    }

    private static String capitalize(String kind) {
        return Character.toUpperCase(kind.charAt(0)) + kind.substring(1);
    }

    // ---- 공통 ----

    private JsonNode parse(String text) {
        if (text == null || text.isBlank()) {
            return json.createArrayNode();
        }
        try {
            return json.readTree(text);
        } catch (RuntimeException e) {
            return json.createArrayNode();
        }
    }

    private static Timestamp start(Query q) {
        return Timestamp.valueOf(q.from().atStartOfDay());
    }

    private static Timestamp end(Query q) {
        return Timestamp.valueOf(q.to().plusDays(1).atStartOfDay());
    }

    private <T> Optional<T> read(String name, List<String> unavailable, Function<JdbcTemplate, T> query) {
        Optional<Target> target = registry.all().stream().filter(t -> t.name().equals(name)).findFirst();
        if (target.isEmpty()) {
            unavailable.add(name);
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(query.apply(target.get().jdbc()));
        } catch (DataAccessException e) {
            log.warn("베타 집계를 읽지 못했습니다 (db={}): {}", name, e.getMostSpecificCause().getMessage());
            unavailable.add(name);
            return Optional.empty();
        }
    }
}
