package com.shise.studio.business;

import com.shise.studio.common.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Component;

/** Shared validation and authorization for teaching workflows. SQL is always server-authored. */
@Component
public class BusinessSupport {
  final Db db;
  final Scope scope;
  final Audit audit;

  public BusinessSupport(Db db, Scope scope, Audit audit) {
    this.db = db;
    this.scope = scope;
    this.audit = audit;
  }

  static Map<String, Object> p(Object... values) {
    Map<String, Object> out = new LinkedHashMap<>();
    for (int i = 0; i < values.length; i += 2) out.put((String) values[i], values[i + 1]);
    return out;
  }

  static long n(Object value) {
    if (value == null) return 0;
    try {
      return new java.math.BigDecimal(value.toString()).longValueExact();
    } catch (Exception e) {
      throw new BusinessException(400, "编号或数量格式不正确");
    }
  }

  static long id(Map<String, Object> body, String key) {
    long v = n(body.get(key));
    if (v <= 0) throw new BusinessException(400, "缺少有效字段：" + key);
    return v;
  }

  static String text(Map<String, Object> body, String key, int max, boolean required) {
    Object value = body.get(key);
    String s = value == null ? "" : value.toString().trim();
    if (required && s.isBlank()) throw new BusinessException(400, "请填写" + key);
    if (s.length() > max) throw new BusinessException(400, key + "内容过长");
    return s;
  }

  static LocalDate date(Object value, LocalDate fallback) {
    if (value == null || value.toString().isBlank()) return fallback;
    try {
      return value instanceof java.sql.Date d
          ? d.toLocalDate()
          : value instanceof LocalDate d ? d : LocalDate.parse(value.toString());
    } catch (Exception e) {
      throw new BusinessException(400, "日期格式应为 yyyy-MM-dd");
    }
  }

  static LocalDateTime time(Object value) {
    if (value instanceof LocalDateTime d) return d;
    if (value instanceof Timestamp t) return t.toLocalDateTime();
    try {
      return LocalDateTime.parse(Objects.toString(value, "").replace(' ', 'T'));
    } catch (Exception e) {
      throw new BusinessException(400, "时间格式应为 yyyy-MM-dd'T'HH:mm:ss");
    }
  }

  static boolean isAdmin() {
    return "ADMIN".equals(Actor.current().role());
  }

  static boolean isStudent() {
    return "STUDENT".equals(Actor.current().role());
  }

  static void write(String... roles) {
    Actor.requireWritable();
    Actor.requireRoles(roles);
  }

  void lock() {
    db.one("SELECT id FROM schedule_guard WHERE id=1 FOR UPDATE", Map.of());
  }

  Map<String, Object> get(String table, long id) {
    if (!Set.of(
            "students",
            "teachers",
            "courses",
            "classes",
            "rooms",
            "enrollments",
            "lessons",
            "lesson_participants",
            "attendance",
            "leaves",
            "makeups",
            "tasks",
            "works",
            "work_versions",
            "files")
        .contains(table)) throw new IllegalArgumentException("Unexpected table");
    Map<String, Object> row = db.one("SELECT * FROM " + table + " WHERE id=#{p.id}", p("id", id));
    if (row == null) throw new BusinessException(404, "记录不存在");
    return row;
  }

  void activeClass(Map<String, Object> row) {
    if (!"ACTIVE".equals(row.get("status"))) throw new BusinessException(409, "班级已结班");
  }

  void lessonScope(Map<String, Object> lesson) {
    if (isStudent()) {
      if (db.count(
              "SELECT COUNT(*) FROM lesson_participants WHERE lesson_id=#{p.lesson} AND"
                  + " student_id=#{p.student}",
              p("lesson", lesson.get("id"), "student", Actor.current().studentId()))
          == 0) throw new BusinessException(403, "不能查看其他学员的课次");
    } else scope.requireTeacherClass(n(lesson.get("classId")));
  }

  boolean visibleLesson(Map<String, Object> lesson) {
    if (isAdmin()) return true;
    if (isStudent())
      return db.count(
              "SELECT COUNT(*) FROM lesson_participants WHERE lesson_id=#{p.lesson} AND"
                  + " student_id=#{p.student}",
              p("lesson", lesson.get("id"), "student", Actor.current().studentId()))
          > 0;
    return scope.canClass(n(lesson.get("classId")));
  }

  void participantScope(Map<String, Object> participant) {
    if (isStudent() && n(participant.get("studentId")) != n(Actor.current().studentId()))
      throw new BusinessException(403, "不能访问其他学员的记录");
    lessonScope(get("lessons", n(participant.get("lessonId"))));
  }

  void ownedFile(long fileId) {
    Map<String, Object> file = get("files", fileId);
    if (n(file.get("ownerId")) != Actor.current().id())
      throw new BusinessException(403, "请使用本人上传的文件");
  }

  void notice(long classId, String title, String content) {
    db.insert(
        "notices",
        p(
            "class_id",
            classId,
            "title",
            title,
            "content",
            content,
            "created_by",
            Actor.current().id()));
  }

  void assertNoStudentConflict(
      long studentId, LocalDateTime start, LocalDateTime end, long exceptLesson) {
    if (db.count(
            "SELECT COUNT(*) FROM lesson_participants lp JOIN lessons l ON l.id=lp.lesson_id WHERE"
                + " lp.student_id=#{p.student} AND lp.status='ACTIVE' AND l.status<>'CANCELLED' AND"
                + " l.id<>#{p.exclude} AND l.start_time<#{p.end} AND l.end_time>#{p.start}",
            p("student", studentId, "start", start, "end", end, "exclude", exceptLesson))
        > 0) throw new BusinessException(409, "学员在此时间已有其他课次安排");
  }
}
