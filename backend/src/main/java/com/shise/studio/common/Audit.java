package com.shise.studio.common;

import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class Audit {
  private final Db db;

  public Audit(Db db) {
    this.db = db;
  }

  public void log(String action, String targetType, Object targetId, String detail) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("user_id", Actor.current().id());
    row.put("action", action);
    row.put("target_type", targetType);
    row.put("target_id", String.valueOf(targetId));
    row.put("detail", detail);
    db.insert("audit_logs", row);
  }
}
