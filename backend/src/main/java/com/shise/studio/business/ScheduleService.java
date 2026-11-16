package com.shise.studio.business;

import static com.shise.studio.business.BusinessSupport.*;

import com.shise.studio.common.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ScheduleService {
  private final BusinessSupport b;

  public ScheduleService(BusinessSupport b) {
    this.b = b;
  }

  public List<Map<String, Object>> lessons(Long classId, String from, String to) {
    String sql =
        "SELECT l.*,c.name class_name,t.name teacher_name,r.name room_name,(SELECT COUNT(*) FROM"
            + " lesson_participants lp WHERE lp.lesson_id=l.id AND lp.status='ACTIVE')"
            + " participant_count FROM lessons l JOIN classes c ON c.id=l.class_id JOIN teachers t"
            + " ON t.id=l.teacher_id JOIN rooms r ON r.id=l.room_id WHERE 1=1";
    Map<String, Object> params = p();
    if (classId != null) {
      sql += " AND l.class_id=#{p.classId}";
      params.put("classId", classId);
    }
    if (from != null && !from.isBlank()) {
      sql += " AND l.start_time>=#{p.start}";
      params.put("start", date(from, null).atStartOfDay());
    }
    if (to != null && !to.isBlank()) {
      sql += " AND l.start_time<#{p.end}";
      params.put("end", date(to, null).plusDays(1).atStartOfDay());
    }
    return b.db.list(sql + " ORDER BY l.start_time DESC,l.id DESC", params).stream()
        .filter(b::visibleLesson)
        .toList();
  }

  private List<Map<String, Object>> members(long classId, LocalDate date) {
    return b.db.list(
        "SELECT DISTINCT student_id FROM enrollments WHERE class_id=#{p.classId} AND"
            + " effective_date<=#{p.date} AND (end_date IS NULL OR end_date>#{p.date})",
        p("classId", classId, "date", date));
  }

  @Transactional
  public Map<String, Object> save(Long lessonId, Map<String, Object> body) {
    write("ADMIN");
    b.lock();
    long classId = id(body, "classId"),
        teacher = id(body, "teacherId"),
        roomId = id(body, "roomId");
    Map<String, Object> cls = b.get("classes", classId), room = b.get("rooms", roomId);
    b.activeClass(cls);
    b.get("teachers", teacher);
    String title = text(body, "title", 200, true);
    LocalDateTime start = time(body.get("startTime")), end = time(body.get("endTime"));
    if (!end.isAfter(start) || Duration.between(start, end).compareTo(Duration.ofHours(12)) > 0)
      throw new BusinessException(400, "结束时间应晚于开始时间，单次课不超过12小时");
    long exclude = lessonId == null ? 0 : lessonId;
    Map<String, Object> original = lessonId == null ? null : b.get("lessons", lessonId);
    String reason = "";
    if (original != null) {
      if (!"SCHEDULED".equals(original.get("status")))
        throw new BusinessException(409, "已完成或已取消课次不能调课");
      if (n(original.get("classId")) != classId)
        throw new BusinessException(400, "调课不能更换班级，请使用转班流程");
      reason = text(body, "reason", 1000, true);
      if (b.db.count(
              "SELECT COUNT(*) FROM attendance a JOIN lesson_participants lp ON"
                  + " lp.id=a.participant_id WHERE lp.lesson_id=#{p.id} AND a.status<>'LEAVE'",
              p("id", lessonId))
          > 0) throw new BusinessException(409, "已有出勤或缺勤的课次不能调课，请先通过更正流程核对记录");
      if (b.db.count(
              "SELECT COUNT(*) FROM makeups m JOIN lesson_participants lp ON"
                  + " lp.id=m.original_participant_id WHERE lp.lesson_id=#{p.id} AND"
                  + " m.status<>'CANCELLED'",
              p("id", lessonId))
          > 0) throw new BusinessException(409, "该课次已有补课安排，请先取消未完成补课再调课");
    }
    if (b.db.count(
            "SELECT COUNT(*) FROM lessons WHERE id<>#{p.exclude} AND status<>'CANCELLED' AND"
                + " start_time<#{p.end} AND end_time>#{p.start} AND (teacher_id=#{p.teacher} OR"
                + " room_id=#{p.room} OR class_id=#{p.classId})",
            p(
                "exclude", exclude, "end", end, "start", start, "teacher", teacher, "room", roomId,
                "classId", classId))
        > 0) throw new BusinessException(409, "排课冲突：教师、教室或班级在此时间已有课次");
    List<Map<String, Object>> students = members(classId, start.toLocalDate());
    Set<Long> studentIds = new LinkedHashSet<>();
    for (Map<String, Object> s : students) studentIds.add(n(s.get("studentId")));
    if (original != null) {
      for (Map<String, Object> makeup :
          b.db.list(
              "SELECT lp.*,origin_l.end_time original_end_time FROM lesson_participants lp JOIN"
                  + " lesson_participants op ON op.id=lp.original_participant_id JOIN lessons"
                  + " origin_l ON origin_l.id=op.lesson_id WHERE lp.lesson_id=#{p.id} AND"
                  + " lp.kind='MAKEUP' AND lp.status='ACTIVE'",
              p("id", lessonId))) {
        if (start.isBefore(time(makeup.get("originalEndTime"))))
          throw new BusinessException(409, "补课时间不能早于原课次结束时间");
        if (!studentIds.add(n(makeup.get("studentId"))))
          throw new BusinessException(409, "调课后补课学员与正常班级名单重复，请先取消对应补课");
      }
    }
    if (studentIds.size() > Math.min(n(cls.get("capacity")), n(room.get("capacity"))))
      throw new BusinessException(409, "班级或教室容量不足");
    for (long student : studentIds) b.assertNoStudentConflict(student, start, end, exclude);
    if (original == null) {
      lessonId =
          b.db.insert(
              "lessons",
              p(
                  "class_id",
                  classId,
                  "teacher_id",
                  teacher,
                  "room_id",
                  roomId,
                  "title",
                  title,
                  "start_time",
                  start,
                  "end_time",
                  end,
                  "status",
                  "SCHEDULED",
                  "created_by",
                  Actor.current().id()));
    } else {
      // Original lessons with already arranged makeups must retain chronological ordering.
      if (b.db.count(
              "SELECT COUNT(*) FROM makeups m JOIN lesson_participants lp ON"
                  + " lp.id=m.original_participant_id JOIN lessons target ON"
                  + " target.id=m.target_lesson_id WHERE lp.lesson_id=#{p.id} AND"
                  + " m.status<>'CANCELLED' AND target.start_time<#{p.end}",
              p("id", lessonId, "end", end))
          > 0) throw new BusinessException(409, "调课时间晚于已安排的补课，请先调整补课安排");
      b.db.update(
          "UPDATE lessons SET"
              + " teacher_id=#{p.teacher},room_id=#{p.room},title=#{p.title},start_time=#{p.start},end_time=#{p.end}"
              + " WHERE id=#{p.id}",
          p(
              "teacher", teacher, "room", roomId, "title", title, "start", start, "end", end, "id",
              lessonId));
      b.db.update(
          "UPDATE lesson_participants SET status='CANCELLED' WHERE lesson_id=#{p.id} AND"
              + " kind='REGULAR'",
          p("id", lessonId));
      for (var leaveAttendance :
          b.db.list(
              "SELECT a.id,a.participant_id FROM attendance a JOIN lesson_participants lp ON"
                  + " lp.id=a.participant_id WHERE lp.lesson_id=#{p.id} AND a.status='LEAVE'",
              p("id", lessonId))) {
        b.audit.log(
            "CORRECT_ATTENDANCE",
            "participant",
            leaveAttendance.get("participantId"),
            "调课撤销旧时间的请假考勤；原因：" + reason);
        b.db.update("DELETE FROM attendance WHERE id=#{p.id}", p("id", leaveAttendance.get("id")));
      }
      b.db.update(
          "UPDATE leaves SET status='CANCELLED',review_comment='课次已调整，请按新课表重新申请' WHERE"
              + " lesson_id=#{p.id} AND status IN ('PENDING','APPROVED')",
          p("id", lessonId));
    }
    for (Map<String, Object> student : students) {
      Map<String, Object> part =
          b.db.one(
              "SELECT * FROM lesson_participants WHERE lesson_id=#{p.lesson} AND"
                  + " student_id=#{p.student}",
              p("lesson", lessonId, "student", student.get("studentId")));
      if (part == null)
        b.db.insert(
            "lesson_participants",
            p(
                "lesson_id",
                lessonId,
                "student_id",
                student.get("studentId"),
                "kind",
                "REGULAR",
                "status",
                "ACTIVE"));
      else
        b.db.update(
            "UPDATE lesson_participants SET"
                + " status='ACTIVE',kind='REGULAR',original_participant_id=NULL WHERE id=#{p.id}",
            p("id", part.get("id")));
    }
    b.audit.log(
        original == null ? "CREATE_LESSON" : "RESCHEDULE",
        "lesson",
        lessonId,
        original == null ? title : "调课原因：" + reason);
    b.notice(
        classId,
        original == null ? "新增课次" : "课次调整",
        title + "：" + start + " 至 " + end + (reason.isBlank() ? "" : "；原因：" + reason));
    return b.get("lessons", lessonId);
  }

  @Transactional
  public void cancel(long id, Map<String, Object> body) {
    write("ADMIN");
    b.lock();
    Map<String, Object> lesson = b.get("lessons", id);
    String reason = text(body, "reason", 1000, true);
    if ("CANCELLED".equals(lesson.get("status"))) return;
    if (b.db.count(
            "SELECT COUNT(*) FROM attendance a JOIN lesson_participants lp ON"
                + " lp.id=a.participant_id WHERE lp.lesson_id=#{p.id} AND a.status IN ('PRESENT','ABSENT')",
            p("id", id))
        > 0) throw new BusinessException(409, "已有出勤或缺勤记录，不能停课抹除教学记录");
    List<Map<String, Object>> makeups =
        b.db.list(
            "SELECT m.* FROM makeups m JOIN lesson_participants op ON"
                + " op.id=m.original_participant_id WHERE m.status<>'CANCELLED' AND"
                + " (m.target_lesson_id=#{p.id} OR op.lesson_id=#{p.id})",
            p("id", id));
    for (Map<String, Object> makeup : makeups) {
      if ("COMPLETED".equals(makeup.get("status")))
        throw new BusinessException(409, "关联补课已完成，不能取消原课次");
      b.db.update(
          "UPDATE makeups SET status='CANCELLED',cancelled_reason=#{p.reason} WHERE id=#{p.id}",
          p("reason", "关联课次停课：" + reason, "id", makeup.get("id")));
      b.db.update(
          "UPDATE lesson_participants SET status='CANCELLED' WHERE id=#{p.id}",
          p("id", makeup.get("targetParticipantId")));
    }
    b.db.update(
        "UPDATE lessons SET status='CANCELLED',cancel_reason=#{p.reason} WHERE id=#{p.id}",
        p("id", id, "reason", reason));
    b.db.update(
        "UPDATE lesson_participants SET status='CANCELLED' WHERE lesson_id=#{p.id}", p("id", id));
    b.db.update(
        "UPDATE leaves SET status='CANCELLED',review_comment=#{p.reason} WHERE lesson_id=#{p.id}"
            + " AND status IN ('PENDING','APPROVED')",
        p("id", id, "reason", "课次停课：" + reason));
    b.notice(
        n(lesson.get("classId")),
        "停课通知",
        lesson.get("title") + " 已停课。原因：" + reason + "。关联补课安排已同步取消。");
    b.audit.log("CANCEL_LESSON", "lesson", id, reason);
  }

  public List<Map<String, Object>> participants(long lessonId) {
    b.lessonScope(b.get("lessons", lessonId));
    return b
        .db
        .list(
            "SELECT lp.*,s.name student_name,a.status attendance_status,a.remark,a.updated_at"
                + " attendance_updated_at FROM lesson_participants lp JOIN students s ON"
                + " s.id=lp.student_id LEFT JOIN attendance a ON a.participant_id=lp.id WHERE"
                + " lp.lesson_id=#{p.id} AND lp.status='ACTIVE' ORDER BY lp.kind,lp.id",
            p("id", lessonId))
        .stream()
        .filter(r -> !isStudent() || n(r.get("studentId")) == n(Actor.current().studentId()))
        .toList();
  }

  @Transactional
  public void attendance(long lessonId, Map<String, Object> body) {
    write("ADMIN", "TEACHER");
    b.lock();
    Map<String, Object> lesson = b.get("lessons", lessonId);
    b.scope.requireTeacherClass(n(lesson.get("classId")));
    if ("CANCELLED".equals(lesson.get("status"))) throw new BusinessException(409, "停课课次不能登记考勤");
    if (time(lesson.get("startTime")).isAfter(LocalDateTime.now()))
      throw new BusinessException(409, "课次尚未开始，不能提前登记出勤");
    Object recordsObj = body.get("records");
    if (!(recordsObj instanceof List<?> records) || records.isEmpty() || records.size() > 500)
      throw new BusinessException(400, "请提交1至500条考勤记录");
    String reason = text(body, "reason", 1000, false);
    Set<Long> seen = new HashSet<>();
    for (Object object : records) {
      if (!(object instanceof Map<?, ?> raw)) throw new BusinessException(400, "考勤数据格式错误");
      Map<String, Object> record = new LinkedHashMap<>();
      raw.forEach((k, v) -> record.put(k.toString(), v));
      long participant = id(record, "participantId");
      if (!seen.add(participant)) throw new BusinessException(400, "同一次提交不能包含重复学员");
      Map<String, Object> part = b.get("lesson_participants", participant);
      if (n(part.get("lessonId")) != lessonId || !"ACTIVE".equals(part.get("status")))
        throw new BusinessException(400, "考勤学员不属于当前有效课次名单");
      String status = text(record, "status", 20, true),
          remark = text(record, "remark", 1000, false);
      if (!Set.of("PRESENT", "ABSENT", "LEAVE").contains(status))
        throw new BusinessException(400, "考勤状态无效");
      Map<String, Object> old =
          b.db.one("SELECT * FROM attendance WHERE participant_id=#{p.id}", p("id", participant));
      if (old != null
          && status.equals(old.get("status"))
          && remark.equals(Objects.toString(old.get("remark"), ""))) continue;
      if (old != null && reason.isBlank()) throw new BusinessException(400, "更正已登记考勤必须填写原因");
      if ("REGULAR".equals(part.get("kind"))
          && "PRESENT".equals(status)
          && b.db.count(
                  "SELECT COUNT(*) FROM makeups WHERE original_participant_id=#{p.id} AND"
                      + " status<>'CANCELLED'",
                  p("id", participant))
              > 0) throw new BusinessException(409, "该原课次已有补课安排，先处理关联补课再更正为出勤");
      if (old == null)
        b.db.insert(
            "attendance",
            p(
                "participant_id",
                participant,
                "status",
                status,
                "remark",
                remark,
                "recorded_by",
                Actor.current().id()));
      else
        b.db.update(
            "UPDATE attendance SET"
                + " status=#{p.status},remark=#{p.remark},recorded_by=#{p.user},updated_at=CURRENT_TIMESTAMP"
                + " WHERE participant_id=#{p.id}",
            p("status", status, "remark", remark, "user", Actor.current().id(), "id", participant));
      if ("MAKEUP".equals(part.get("kind"))) {
        b.db.update(
            "UPDATE makeups SET status=#{p.status},completed_at=#{p.completed} WHERE"
                + " target_participant_id=#{p.id} AND status<>'CANCELLED'",
            p(
                "status",
                "PRESENT".equals(status) ? "COMPLETED" : "ARRANGED",
                "completed",
                "PRESENT".equals(status) ? LocalDateTime.now() : null,
                "id",
                participant));
      }
      if (!"LEAVE".equals(status))
        b.db.update(
            "UPDATE leaves SET status='CANCELLED',review_comment=#{p.reason} WHERE"
                + " participant_id=#{p.id} AND status IN ('PENDING','APPROVED')",
            p("id", participant, "reason", (old == null ? "已登记实际考勤：" : "考勤更正：")
                + status + (reason.isBlank() ? "" : "；" + reason)));
      b.audit.log(
          old == null ? "ATTENDANCE" : "CORRECT_ATTENDANCE",
          "participant",
          participant,
          (old == null ? "" : Objects.toString(old.get("status")) + " → ") + status + "；" + reason);
    }
    long pending =
        b.db.count(
            "SELECT COUNT(*) FROM lesson_participants lp LEFT JOIN attendance a ON"
                + " a.participant_id=lp.id WHERE lp.lesson_id=#{p.id} AND lp.status='ACTIVE' AND"
                + " a.id IS NULL",
            p("id", lessonId));
    if (pending == 0)
      b.db.update("UPDATE lessons SET status='COMPLETED' WHERE id=#{p.id}", p("id", lessonId));
  }
}
