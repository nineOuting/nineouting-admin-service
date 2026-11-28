package com.nineouting.backend.admin.db;

import com.nineouting.backend.admin.error.AdminException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * public 스키마의 표, 열, 기본 키를 DB 카탈로그에서 읽는다.
 * 표와 열 이름은 여기서 읽은 것만 SQL에 넣는다 (화면이 보낸 이름을 그대로 쓰지 않는다).
 */
@Component
public class SchemaReader {

    /** 기본 키가 있어도 읽기만 하는 표. Flyway 이력을 고치면 다음 배포가 실패한다 */
    static final Set<String> READ_ONLY_TABLES = Set.of("flyway_schema_history");

    private static final String TABLES = """
            select c.relname as name, greatest(c.reltuples, 0)::bigint as estimate
            from pg_class c join pg_namespace n on n.oid = c.relnamespace
            where n.nspname = 'public' and c.relkind in ('r', 'p')
            order by c.relname""";

    private static final String TABLE_NAME = """
            select c.relname from pg_class c join pg_namespace n on n.oid = c.relnamespace
            where n.nspname = 'public' and c.relkind in ('r', 'p') and c.relname = ?""";

    private static final String COLUMNS = """
            select a.attname as name, format_type(a.atttypid, a.atttypmod) as type, not a.attnotnull as nullable,
                   pg_get_expr(d.adbin, d.adrelid) as default_value, a.attidentity as identity, a.attgenerated as generated,
                   array(select e.enumlabel::text from pg_enum e where e.enumtypid = a.atttypid
                         order by e.enumsortorder) as enum_values
            from pg_attribute a
            join pg_class c on c.oid = a.attrelid
            join pg_namespace n on n.oid = c.relnamespace
            left join pg_attrdef d on d.adrelid = a.attrelid and d.adnum = a.attnum
            where n.nspname = 'public' and c.relname = ? and a.attnum > 0 and not a.attisdropped
            order by a.attnum""";

    private static final String PRIMARY_KEY = """
            select a.attname
            from pg_index i
            join pg_class c on c.oid = i.indrelid
            join pg_namespace n on n.oid = c.relnamespace
            join unnest(i.indkey) with ordinality as k(attnum, ord) on true
            join pg_attribute a on a.attrelid = c.oid and a.attnum = k.attnum
            where n.nspname = 'public' and c.relname = ? and i.indisprimary
            order by k.ord""";

    /** 열 하나에만 걸린 CHECK 제약 (허용 값 목록을 찾는다) */
    private static final String CHECKS = """
            select a.attname as name, pg_get_constraintdef(con.oid) as definition
            from pg_constraint con
            join pg_class c on c.oid = con.conrelid
            join pg_namespace n on n.oid = c.relnamespace
            join pg_attribute a on a.attrelid = c.oid and a.attnum = con.conkey[1]
            where n.nspname = 'public' and c.relname = ? and con.contype = 'c' and cardinality(con.conkey) = 1""";

    /** CHECK ((status)::text = ANY ((ARRAY['A'::character varying, ...])::text[])) 모양만 허용 값 목록으로 본다 */
    private static final Pattern ANY_ARRAY = Pattern.compile("=\\s*ANY\\s*\\(+\\s*ARRAY\\[(.*)]", Pattern.DOTALL);
    private static final Pattern LITERAL = Pattern.compile("'((?:[^']|'')*)'");

    public record TableSummary(String name, long estimatedRows) {
    }

    /**
     * @param writable      추가, 수정 때 값을 넣을 수 있는지 (항상 생성되는 identity, 계산 열은 못 넣음)
     * @param allowedValues 넣을 수 있는 값이 정해진 열(enum 형식, CHECK 제약)의 값 목록. 정해지지 않았으면 비어 있다 (REQ-219)
     */
    public record Column(String name, String type, boolean nullable, String defaultValue, boolean primaryKey,
                         boolean writable, List<String> allowedValues) {
    }

    public record Table(String name, List<Column> columns, List<String> primaryKey, boolean readOnly,
                        String readOnlyReason) {

        public Column column(String name) {
            return columns.stream().filter(c -> c.name().equals(name)).findFirst()
                    .orElseThrow(() -> AdminException.badRequest("열을 찾을 수 없습니다: " + name));
        }
    }

    public List<TableSummary> tables(JdbcTemplate jdbc) {
        return jdbc.query(TABLES, (rs, i) -> new TableSummary(rs.getString("name"), rs.getLong("estimate")));
    }

    public Table table(JdbcTemplate jdbc, String requested) {
        // SQL에 넣을 표 이름은 화면이 보낸 글자가 아니라 DB 카탈로그에서 읽은 이름을 쓴다
        String name = jdbc.queryForList(TABLE_NAME, String.class, requested).stream().findFirst()
                .orElseThrow(() -> AdminException.notFound("표를 찾을 수 없습니다: " + requested));
        List<String> primaryKey = jdbc.queryForList(PRIMARY_KEY, String.class, name);
        Map<String, List<String>> checked = new HashMap<>();
        jdbc.query(CHECKS, rs -> {
            List<String> values = allowedValues(rs.getString("definition"));
            if (!values.isEmpty()) {
                checked.put(rs.getString("name"), values);
            }
        }, name);
        List<Column> columns = jdbc.query(COLUMNS, (rs, i) -> {
            String column = rs.getString("name");
            boolean alwaysIdentity = "a".equals(rs.getString("identity"));
            boolean generated = rs.getString("generated") != null && !rs.getString("generated").isEmpty();
            String[] enumValues = (String[]) rs.getArray("enum_values").getArray();
            List<String> allowed = enumValues.length > 0 ? List.of(enumValues) : checked.getOrDefault(column, List.of());
            return new Column(column, rs.getString("type"), rs.getBoolean("nullable"), rs.getString("default_value"),
                    primaryKey.contains(column), !alwaysIdentity && !generated, allowed);
        }, name);
        if (columns.isEmpty()) {
            throw AdminException.notFound("표를 찾을 수 없습니다: " + name);
        }
        String reason = null;
        if (READ_ONLY_TABLES.contains(name)) {
            reason = "배포 이력 표라 읽기만 할 수 있습니다";
        } else if (primaryKey.isEmpty()) {
            reason = "기본 키가 없어 한 행을 골라 고칠 수 없습니다";
        }
        return new Table(name, columns, primaryKey, reason != null, reason);
    }

    /** CHECK 정의에서 = ANY (ARRAY['A', 'B']) 의 글자 값들. 다른 모양이면 비어 있다 */
    static List<String> allowedValues(String definition) {
        Matcher array = ANY_ARRAY.matcher(definition == null ? "" : definition);
        if (!array.find()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        Matcher literal = LITERAL.matcher(array.group(1));
        while (literal.find()) {
            values.add(literal.group(1).replace("''", "'"));
        }
        return List.copyOf(values);
    }
}
