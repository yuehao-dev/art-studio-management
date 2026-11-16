package com.shise.studio.common;

import java.util.*;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** SQL只由服务端代码构造；所有用户值通过MyBatis参数绑定，不拼接到SQL。 */
@Component
public class Db {
  private final SqlMapper mapper;

  public Db(SqlMapper mapper) {
    this.mapper = mapper;
  }

  public List<Map<String, Object>> list(String sql, Map<String, Object> p) {
    return mapper.list(sql, p == null ? Map.of() : p).stream().map(this::normalize).toList();
  }

  public Map<String, Object> one(String sql, Map<String, Object> p) {
    var rows = list(sql, p);
    return rows.isEmpty() ? null : rows.get(0);
  }

  public long count(String sql, Map<String, Object> p) {
    var row = one(sql, p);
    return row == null ? 0 : ((Number) row.values().iterator().next()).longValue();
  }

  public int update(String sql, Map<String, Object> p) {
    return mapper.update(sql, p == null ? Map.of() : p);
  }

  public long insert(String table, Map<String, Object> row) {
    identifier(table);
    row.keySet().forEach(this::identifier);
    Map<String, Object> p = new LinkedHashMap<>(row);
    String columns = row.keySet().stream().map(k -> "`" + k + "`").collect(Collectors.joining(","));
    String values =
        row.keySet().stream().map(k -> "#{p." + k + "}").collect(Collectors.joining(","));
    mapper.insert("INSERT INTO `" + table + "` (" + columns + ") VALUES (" + values + ")", p);
    return ((Number) p.get("id")).longValue();
  }

  private void identifier(String s) {
    if (!s.matches("[a-z][a-z0-9_]*")) throw new IllegalArgumentException("非法SQL标识符");
  }

  private Map<String, Object> normalize(Map<String, Object> row) {
    Map<String, Object> out = new LinkedHashMap<>();
    row.forEach(
        (k, v) -> {
          StringBuilder name = new StringBuilder();
          boolean upper = false;
          for (char c : k.toCharArray()) {
            if (c == '_') {
              upper = true;
              continue;
            }
            name.append(upper ? Character.toUpperCase(c) : c);
            upper = false;
          }
          Object value = v;
          if (v instanceof java.sql.Timestamp t) value = t.toLocalDateTime();
          else if (v instanceof java.sql.Date d) value = d.toLocalDate();
          out.put(name.toString(), value);
        });
    return out;
  }
}
