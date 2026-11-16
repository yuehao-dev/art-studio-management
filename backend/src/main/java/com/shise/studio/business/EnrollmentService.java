package com.shise.studio.business;

import static com.shise.studio.business.BusinessSupport.*;

import com.shise.studio.common.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EnrollmentService {
  private final BusinessSupport b;

  public EnrollmentService(BusinessSupport b) {
    this.b = b;
  }

  public List<Map<String, Object>> list() {
    return b
        .db
        .list(
            "SELECT e.*,s.name student_name,c.name class_name,co.name course_name FROM enrollments"
                + " e JOIN students s ON s.id=e.student_id JOIN classes c ON c.id=e.class_id JOIN"
                + " courses co ON co.id=c.course_id ORDER BY e.id DESC",
            Map.of())
        .stream()
        .filter(
            r ->
                isStudent()
                    ? n(r.get("studentId")) == n(Actor.current().studentId())
                    : b.scope.canClass(n(r.get("classId"))))
        .toList();
  }

  @Transactional
  public Map<String, Object> enroll(Map<String, Object> body) {
    write("ADMIN");
    b.lock();
    long student = id(body, "studentId"), classId = id(body, "classId");
    b.get("students", student);
    LocalDate effective = date(body.get("effectiveDate"), LocalDate.now());
    long enrollment = enrollInternal(student, classId, effective);
    b.audit.log("ENROLL", "enrollment", enrollment, "学员报名分班");
    return b.get("enrollments", enrollment);
  }

  private long enrollInternal(long student, long classId, LocalDate effective) {
    Map<String, Object> cls = b.get("classes", classId);
    b.activeClass(cls);
    if (b.db.count(
            "SELECT COUNT(*) FROM enrollments WHERE student_id=#{p.student} AND"
                + " class_id=#{p.classId} AND (end_date IS NULL OR end_date>#{p.effective})",
            p("student", student, "classId", classId, "effective", effective))
        > 0) throw new BusinessException(409, "该学员已报名此班级");
    long members = EnrollmentPeriods.peakOccupancy(b.db, classId, effective);
    if (members >= n(cls.get("capacity"))) throw new BusinessException(409, "班级名额已满");
    List<Map<String, Object>> lessons =
        b.db.list(
            "SELECT * FROM lessons WHERE class_id=#{p.classId} AND status='SCHEDULED' AND"
                + " start_time>=#{p.start} AND start_time>#{p.now}",
            p("classId", classId, "start", effective.atStartOfDay(), "now", LocalDateTime.now()));
    for (Map<String, Object> lesson : lessons) {
      long lessonId = n(lesson.get("id"));
      b.assertNoStudentConflict(
          student, time(lesson.get("startTime")), time(lesson.get("endTime")), lessonId);
      long existing =
          b.db.count(
              "SELECT COUNT(*) FROM lesson_participants WHERE lesson_id=#{p.lesson} AND"
                  + " status='ACTIVE'",
              p("lesson", lessonId));
      Map<String, Object> room = b.get("rooms", n(lesson.get("roomId")));
      if (existing >= Math.min(n(cls.get("capacity")), n(room.get("capacity"))))
        throw new BusinessException(409, "已安排课次的剩余名额不足");
      Map<String, Object> old =
          b.db.one(
              "SELECT * FROM lesson_participants WHERE lesson_id=#{p.lesson} AND"
                  + " student_id=#{p.student}",
              p("lesson", lessonId, "student", student));
      if (old != null && "ACTIVE".equals(old.get("status")))
        throw new BusinessException(409, "该学员已参加此班级课次");
    }
    long eid =
        b.db.insert(
            "enrollments",
            p(
                "student_id",
                student,
                "class_id",
                classId,
                "effective_date",
                effective,
                "status",
                "ACTIVE"));
    for (Map<String, Object> lesson : lessons) {
      Map<String, Object> old =
          b.db.one(
              "SELECT * FROM lesson_participants WHERE lesson_id=#{p.lesson} AND"
                  + " student_id=#{p.student}",
              p("lesson", lesson.get("id"), "student", student));
      if (old == null)
        b.db.insert(
            "lesson_participants",
            p(
                "lesson_id",
                lesson.get("id"),
                "student_id",
                student,
                "kind",
                "REGULAR",
                "status",
                "ACTIVE"));
      else
        b.db.update(
            "UPDATE lesson_participants SET"
                + " status='ACTIVE',kind='REGULAR',original_participant_id=NULL WHERE id=#{p.id}",
            p("id", old.get("id")));
    }
    return eid;
  }

  @Transactional
  public Map<String, Object> transfer(long id, Map<String, Object> body) {
    write("ADMIN");
    b.lock();
    Map<String, Object> original = b.get("enrollments", id);
    if (!"ACTIVE".equals(original.get("status"))) throw new BusinessException(409, "只能转出有效报名");
    long target = id(body, "classId"), student = n(original.get("studentId"));
    if (target == n(original.get("classId"))) throw new BusinessException(400, "转入班级不能与原班相同");
    LocalDate effective = date(body.get("effectiveDate"), LocalDate.now());
    if (effective.isBefore(LocalDate.now())
        || effective.isBefore(date(original.get("effectiveDate"), LocalDate.now())))
      throw new BusinessException(400, "转班日期不得早于今天或原报名日期");
    String reason = text(body, "reason", 1000, true);
    List<Map<String, Object>> outgoing =
        b.db.list(
            "SELECT lp.*,l.start_time FROM lesson_participants lp JOIN lessons l ON l.id=lp.lesson_id WHERE"
                + " lp.student_id=#{p.student} AND l.class_id=#{p.classId} AND lp.kind='REGULAR'"
                + " AND lp.status='ACTIVE' AND l.status<>'CANCELLED' AND l.start_time>=#{p.start}",
            p(
                "student",
                student,
                "classId",
                original.get("classId"),
                "start",
                effective.atStartOfDay()));
    for (Map<String, Object> part : outgoing) {
      if (!time(part.get("startTime")).isAfter(LocalDateTime.now()))
        throw new BusinessException(409, "转班生效当天已有开始的课次，请将生效日期设为明天或以后");
      if (b.db.count(
              "SELECT COUNT(*) FROM attendance WHERE participant_id=#{p.id} AND status<>'LEAVE'",
              p("id", part.get("id")))
          > 0) throw new BusinessException(409, "转班生效后的课次已有出勤或缺勤，请调整生效日期");
      if (b.db.count(
              "SELECT COUNT(*) FROM makeups WHERE original_participant_id=#{p.id} AND"
                  + " status<>'CANCELLED'",
              p("id", part.get("id")))
          > 0) throw new BusinessException(409, "转班涉及有效补课，请先处理补课");
      b.db.update(
          "UPDATE lesson_participants SET status='CANCELLED' WHERE id=#{p.id}",
          p("id", part.get("id")));
      if (b.db.update(
              "DELETE FROM attendance WHERE participant_id=#{p.id} AND status='LEAVE'",
              p("id", part.get("id"))) > 0)
        b.audit.log("CORRECT_ATTENDANCE", "participant", part.get("id"),
            "转班撤销原班未来课次的请假考勤；原因：" + reason);
      b.db.update(
          "UPDATE leaves SET status='CANCELLED',review_comment='转班取消原课次' WHERE"
              + " participant_id=#{p.id} AND status IN ('PENDING','APPROVED')",
          p("id", part.get("id")));
    }
    b.db.update(
        "UPDATE enrollments SET status='TRANSFERRED',end_date=#{p.date} WHERE id=#{p.id}",
        p("date", effective, "id", id));
    long next = enrollInternal(student, target, effective);
    long transfer =
        b.db.insert(
            "transfers",
            p(
                "enrollment_id",
                id,
                "new_enrollment_id",
                next,
                "student_id",
                student,
                "from_class_id",
                original.get("classId"),
                "to_class_id",
                target,
                "effective_date",
                effective,
                "reason",
                reason,
                "created_by",
                Actor.current().id()));
    b.audit.log("TRANSFER", "enrollment", id, "生效日期：" + effective + "；原因：" + reason);
    b.notice(n(original.get("classId")), "班级成员调整", "有学员于 " + effective + " 转班，历史考勤和作品记录保留。");
    b.notice(target, "新学员转入", "班级有新学员于 " + effective + " 转入，请查看班级名单。");
    return p("id", transfer, "enrollmentId", next);
  }

  @Transactional
  public void close(long classId) {
    write("ADMIN");
    b.lock();
    Map<String, Object> cls = b.get("classes", classId);
    b.activeClass(cls);
    if (b.db.count(
            "SELECT COUNT(*) FROM lessons WHERE class_id=#{p.id} AND status='SCHEDULED'",
            p("id", classId))
        > 0) throw new BusinessException(409, "还有未完成课次，请先登记考勤或停课");
    if (b.db.count(
            "SELECT COUNT(*) FROM makeups m JOIN lesson_participants lp ON"
                + " lp.id=m.original_participant_id JOIN lessons l ON l.id=lp.lesson_id WHERE"
                + " l.class_id=#{p.id} AND m.status='ARRANGED'",
            p("id", classId))
        > 0) throw new BusinessException(409, "还有待完成补课，不能结班");
    b.db.update("UPDATE classes SET status='CLOSED' WHERE id=#{p.id}", p("id", classId));
    b.db.update(
        "UPDATE enrollments SET status='CLOSED',end_date=COALESCE(end_date,#{p.today}) WHERE"
            + " class_id=#{p.id} AND status='ACTIVE'",
        p("id", classId, "today", LocalDate.now().plusDays(1)));
    b.audit.log("CLOSE_CLASS", "class", classId, "班级结班，历史教学记录保留");
  }
}
