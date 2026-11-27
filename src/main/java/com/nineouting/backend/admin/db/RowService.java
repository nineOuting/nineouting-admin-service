package com.nineouting.backend.admin.db;

import com.nineouting.backend.admin.db.DatabaseRegistry.Target;
import com.nineouting.backend.admin.db.SchemaReader.Column;
import com.nineouting.backend.admin.db.SchemaReader.Table;
import com.nineouting.backend.admin.error.AdminException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

/**
 * 표 하나의 행 조회, 추가, 수정, 삭제. 수정, 삭제는 기본 키로 정확히 한 행만 바꾼다 (아니면 되돌린다).
 * 값은 모두 글자로 주고받고 DB가 열 형식으로 바꾼다 (?::형식). 이름은 SchemaReader가 읽은 것만 쓴다.
 */
@Service
public class RowService {

    private static final Logger audit = LoggerFactory.getLogger("admin.audit");
    private static final int MAX_PAGE_SIZE = 200;
    /** 기록에 값을 남기지 않을 열 (비밀번호 해시, 토큰 등) */
    private static final Pattern SECRET_COLUMN = Pattern.compile("password|secret|token", Pattern.CASE_INSENSITIVE);

    private final SchemaReader schema;

    public RowService(SchemaReader schema) {
        this.schema = schema;
    }

    public record Page(List<Map<String, String>> rows, long total, int page, int size) {
    }

    public Page page(Target target, String tableName, int page, int size, String sort, String direction,
                     String filterColumn, String filter) {
        Table table = schema.table(target.jdbc(), tableName);
        int pageSize = Math.clamp(size, 1, MAX_PAGE_SIZE);
        int pageNumber = Math.max(page, 0);
        List<Object> args = new ArrayList<>();
        String where = "";
        if (filter != null && !filter.isBlank()) {
            if (filterColumn != null && !filterColumn.isBlank()) {
                where = " where " + quote(table.column(filterColumn).name()) + "::text ilike ?";
                args.add("%" + filter + "%");
            } else {
                // 열을 고르지 않으면 아무 열에나 들어 있으면 보인다
                where = " where " + table.columns().stream().map(c -> quote(c.name()) + "::text ilike ?")
                        .collect(Collectors.joining(" or ", "(", ")"));
                table.columns().forEach(c -> args.add("%" + filter + "%"));
            }
        }
        String order = orderBy(table, sort, direction);
        long total = target.jdbc().queryForObject("select count(*) from " + quote(table.name()) + where, Long.class,
                args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(pageSize);
        pageArgs.add((long) pageNumber * pageSize);
        List<Map<String, String>> rows = target.jdbc().query("select " + selectList(table) + " from " + quote(table.name())
                + where + order + " limit ? offset ?", mapper(table), pageArgs.toArray());
        return new Page(rows, total, pageNumber, pageSize);
    }

    public Map<String, String> insert(Target target, String tableName, Map<String, String> values) {
        Table table = writable(target, tableName);
        Map<String, String> given = values == null ? Map.of() : values;
        List<Column> columns = given.keySet().stream().map(table::column).toList();
        columns.forEach(c -> requireWritable(c));
        String sql;
        Object[] args;
        if (columns.isEmpty()) {
            sql = "insert into " + quote(table.name()) + " default values returning " + selectList(table);
            args = new Object[0];
        } else {
            sql = "insert into " + quote(table.name())
                    + columns.stream().map(c -> quote(c.name())).collect(Collectors.joining(", ", " (", ")"))
                    + columns.stream().map(RowService::placeholder).collect(Collectors.joining(", ", " values (", ")"))
                    + " returning " + selectList(table);
            args = columns.stream().map(c -> given.get(c.name())).toArray();
        }
        Map<String, String> created = target.transaction().execute(status ->
                target.jdbc().queryForObject(sql, mapper(table), args));
        log("insert", target, table, keyOf(table, created), null, created);
        return created;
    }

    public Map<String, String> update(Target target, String tableName, Map<String, String> key,
                                      Map<String, String> values) {
        Table table = writable(target, tableName);
        if (values == null || values.isEmpty()) {
            throw AdminException.badRequest("바꿀 값이 없습니다");
        }
        List<Column> columns = values.keySet().stream().map(table::column).toList();
        columns.forEach(c -> requireWritable(c));
        Where where = where(table, key);
        String sql = "update " + quote(table.name()) + " set "
                + columns.stream().map(c -> quote(c.name()) + " = " + placeholder(c)).collect(Collectors.joining(", "))
                + where.sql() + " returning " + selectList(table);
        List<Object> args = new ArrayList<>();
        columns.forEach(c -> args.add(values.get(c.name())));
        args.addAll(where.args());
        return target.transaction().execute(status -> {
            Map<String, String> before = single(target, table, where);
            List<Map<String, String>> changed = target.jdbc().query(sql, mapper(table), args.toArray());
            if (changed.size() != 1) {
                status.setRollbackOnly();
                throw AdminException.notFound("바꿀 행을 찾을 수 없습니다");
            }
            log("update", target, table, key, before, changed.getFirst());
            return changed.getFirst();
        });
    }

    public Map<String, String> delete(Target target, String tableName, Map<String, String> key) {
        Table table = writable(target, tableName);
        Where where = where(table, key);
        String sql = "delete from " + quote(table.name()) + where.sql() + " returning " + selectList(table);
        return target.transaction().execute(status -> {
            List<Map<String, String>> deleted = target.jdbc().query(sql, mapper(table), where.args().toArray());
            if (deleted.size() != 1) {
                status.setRollbackOnly();
                throw AdminException.notFound("지울 행을 찾을 수 없습니다");
            }
            log("delete", target, table, key, deleted.getFirst(), null);
            return deleted.getFirst();
        });
    }

    private Table writable(Target target, String tableName) {
        Table table = schema.table(target.jdbc(), tableName);
        if (table.readOnly()) {
            throw AdminException.readOnly(table.readOnlyReason());
        }
        return table;
    }

    private static void requireWritable(Column column) {
        if (!column.writable()) {
            throw AdminException.badRequest("DB가 만드는 열이라 값을 넣을 수 없습니다: " + column.name());
        }
    }

    private record Where(String sql, List<Object> args) {
    }

    /** 기본 키 열을 빠짐없이, 그 열만 받는다 */
    private static Where where(Table table, Map<String, String> key) {
        if (key == null || !key.keySet().equals(new HashSet<>(table.primaryKey()))) {
            throw AdminException.badRequest("기본 키(" + String.join(", ", table.primaryKey()) + ")를 모두 주어야 합니다");
        }
        List<Object> args = new ArrayList<>();
        String sql = table.primaryKey().stream().map(name -> {
            if (key.get(name) == null) {
                throw AdminException.badRequest("기본 키 값이 비어 있습니다: " + name);
            }
            args.add(key.get(name));
            return quote(name) + " = " + placeholder(table.column(name));
        }).collect(Collectors.joining(" and ", " where ", ""));
        return new Where(sql, args);
    }

    private static Map<String, String> single(Target target, Table table, Where where) {
        List<Map<String, String>> rows = target.jdbc().query("select " + selectList(table) + " from " + quote(table.name())
                + where.sql(), mapper(table), where.args().toArray());
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static String orderBy(Table table, String sort, String direction) {
        String dir = direction == null || direction.isBlank() ? "asc" : direction.toLowerCase(Locale.ROOT);
        if (!dir.equals("asc") && !dir.equals("desc")) {
            throw AdminException.badRequest("정렬 방향은 asc나 desc입니다");
        }
        List<String> parts = new ArrayList<>();
        if (sort != null && !sort.isBlank()) {
            parts.add(quote(table.column(sort).name()) + " " + dir + " nulls last");
        }
        // 같은 값끼리 순서가 바뀌지 않게 기본 키로 한 번 더 (기본 키가 없으면 첫 열)
        List<String> tieBreak = table.primaryKey().isEmpty() ? List.of(table.columns().getFirst().name()) : table.primaryKey();
        tieBreak.stream().filter(c -> !c.equals(sort)).forEach(c -> parts.add(quote(c) + " " + dir));
        return " order by " + String.join(", ", parts);
    }

    private static String selectList(Table table) {
        return table.columns().stream().map(c -> quote(c.name()) + "::text as " + quote(c.name()))
                .collect(Collectors.joining(", "));
    }

    private static RowMapper<Map<String, String>> mapper(Table table) {
        return (rs, i) -> {
            Map<String, String> row = new LinkedHashMap<>();
            for (int index = 0; index < table.columns().size(); index++) {
                row.put(table.columns().get(index).name(), rs.getString(index + 1));
            }
            return row;
        };
    }

    private static String placeholder(Column column) {
        return "?::" + column.type();
    }

    static String quote(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }

    private static Map<String, String> keyOf(Table table, Map<String, String> row) {
        Map<String, String> key = new LinkedHashMap<>();
        if (row != null) {
            table.primaryKey().forEach(name -> key.put(name, row.get(name)));
        }
        return key;
    }

    /** 누가 언제 무엇을 바꿨는지. 비밀번호, 토큰 같은 열은 값을 가린다 */
    private static void log(String action, Target target, Table table, Map<String, String> key,
                            Map<String, String> before, Map<String, String> after) {
        audit.info("admin {} db={} table={} key={} before={} after={}", action, target.name(), table.name(), key,
                mask(before), mask(after));
    }

    private static Map<String, String> mask(Map<String, String> row) {
        if (row == null) {
            return null;
        }
        Map<String, String> masked = new LinkedHashMap<>();
        row.forEach((k, v) -> masked.put(k, v != null && SECRET_COLUMN.matcher(k).find() ? "***" : v));
        return masked;
    }
}
