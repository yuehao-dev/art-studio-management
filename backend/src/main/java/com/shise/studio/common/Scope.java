package com.shise.studio.common;

import java.util.*;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.stereotype.Component;

@Component
public class Scope {
  private final Db db;

  public Scope(Db db) {
    this.db = db;
  }

  public boolean canClass(long id) {
    var a = Actor.current();
    if (a.role().equals("ADMIN")) return true;
    if (a.role().equals("TEACHER")) return teacherClass(id);
    if (a.studentId() == null) return false;
    var p = Map.<String, Object>of("id", id, "sid", a.studentId());
    return db.count(
                "SELECT COUNT(*) FROM enrollments WHERE class_id=#{p.id} AND student_id=#{p.sid}",
                p)
            > 0
        || db.count(
                "SELECT COUNT(*) FROM lesson_participants p JOIN lessons l ON l.id=p.lesson_id"
                    + " WHERE l.class_id=#{p.id} AND p.student_id=#{p.sid}",
                p)
            > 0;
  }

  public boolean teacherClass(long id) {
    var a = Actor.current();
    if (a.role().equals("ADMIN")) return true;
    if (!a.role().equals("TEACHER") || a.teacherId() == null) return false;
    var p = Map.<String, Object>of("id", id, "tid", a.teacherId());
    return db.count("SELECT COUNT(*) FROM classes WHERE id=#{p.id} AND teacher_id=#{p.tid}", p) > 0
        || db.count(
                "SELECT COUNT(*) FROM lessons WHERE class_id=#{p.id} AND teacher_id=#{p.tid}", p)
            > 0;
  }

  public void requireClass(long id) {
    if (!canClass(id)) throw new BusinessException(403, "不可访问此班级");
  }

  /** 历史班级可查阅，但新作品提交要求当前仍处于有效报名区间。结束日期不包含当天。 */
  public boolean hasCurrentEnrollment(long classId) {
    var a = Actor.current();
    if (!a.role().equals("STUDENT") || a.studentId() == null) return false;
    return db.count(
        "SELECT COUNT(*) FROM enrollments e JOIN classes c ON c.id=e.class_id"
            + " WHERE e.class_id=#{p.id} AND e.student_id=#{p.sid} AND c.status='ACTIVE'"
            + " AND e.effective_date<=#{p.today} AND (e.end_date IS NULL OR e.end_date>#{p.today})",
        Map.of("id", classId, "sid", a.studentId(),
            "today", LocalDate.now(ZoneId.of("Asia/Shanghai")))) > 0;
  }

  public void requireCurrentEnrollment(long classId) {
    if (!hasCurrentEnrollment(classId))
      throw new BusinessException(403, "仅可向当前在读班级提交作品；已转出或结班的作品可继续查看");
  }

  public void requireTeacherClass(long id) {
    Actor.requireRoles("ADMIN", "TEACHER");
    if (!teacherClass(id)) throw new BusinessException(403, "只能操作本人任课班级");
  }

  public boolean canStudent(long id) {
    var a = Actor.current();
    if (a.role().equals("ADMIN")) return true;
    if (a.role().equals("STUDENT")) return a.studentId() != null && a.studentId() == id;
    if (a.teacherId() == null) return false;
    var p = Map.<String, Object>of("sid", id, "tid", a.teacherId());
    return db.count(
                "SELECT COUNT(*) FROM enrollments e JOIN classes c ON c.id=e.class_id WHERE"
                    + " e.student_id=#{p.sid} AND c.teacher_id=#{p.tid}",
                p)
            > 0
        || db.count(
                "SELECT COUNT(*) FROM lesson_participants p JOIN lessons l ON l.id=p.lesson_id"
                    + " WHERE p.student_id=#{p.sid} AND l.teacher_id=#{p.tid}",
                p)
            > 0;
  }

  public void requireStudent(long id) {
    if (!canStudent(id)) throw new BusinessException(403, "不可访问其他学员资料");
  }
}
