package com.shise.studio.business;

import static com.shise.studio.business.BusinessSupport.*;

import com.shise.studio.common.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LeaveMakeupService {
  private final BusinessSupport b;

  public LeaveMakeupService(BusinessSupport b) {
    this.b = b;
  }

  public List<Map<String, Object>> leaves() {
    return b
        .db
        .list(
            "SELECT v.*,s.name student_name,l.title lesson_title,l.start_time,c.name"
                + " class_name,l.class_id FROM leaves v JOIN students s ON s.id=v.student_id JOIN"
                + " lessons l ON l.id=v.lesson_id JOIN classes c ON c.id=l.class_id ORDER BY v.id"
                + " DESC",
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
  public Map<String, Object> apply(Map<String, Object> body) {
    write("STUDENT");
    b.lock();
    long lessonId = id(body, "lessonId");
    String reason = text(body, "reason", 1000, true);
    Map<String, Object> lesson = b.get("lessons", lessonId);
    b.lessonScope(lesson);
    if (!"SCHEDULED".equals(lesson.get("status"))
        || !time(lesson.get("startTime")).isAfter(LocalDateTime.now()))
      throw new BusinessException(409, "只能为尚未开始的有效课次申请请假");
    Map<String, Object> part =
        b.db.one(
            "SELECT * FROM lesson_participants WHERE lesson_id=#{p.lesson} AND"
                + " student_id=#{p.student} AND status='ACTIVE'",
            p("lesson", lessonId, "student", Actor.current().studentId()));
    if (part == null) throw new BusinessException(403, "不在本课次学员名单中");
    if ("MAKEUP".equals(part.get("kind")))
      throw new BusinessException(409, "补课不能参加时请联系管理员取消并重新安排，避免形成多层补课");
    Map<String, Object> previous =
        b.db.one("SELECT * FROM leaves WHERE participant_id=#{p.id}", p("id", part.get("id")));
    long id;
    if (previous != null) {
      if (Set.of("PENDING", "APPROVED").contains(previous.get("status")))
        throw new BusinessException(409, "本课次已有待审批或已通过的请假");
      id = n(previous.get("id"));
      b.db.update(
          "UPDATE leaves SET"
              + " reason=#{p.reason},status='PENDING',review_comment=NULL,reviewed_by=NULL,reviewed_at=NULL,created_at=CURRENT_TIMESTAMP"
              + " WHERE id=#{p.id}",
          p("id", id, "reason", reason));
    } else
      id =
          b.db.insert(
              "leaves",
              p(
                  "participant_id",
                  part.get("id"),
                  "student_id",
                  Actor.current().studentId(),
                  "lesson_id",
                  lessonId,
                  "reason",
                  reason,
                  "status",
                  "PENDING"));
    b.audit.log("APPLY_LEAVE", "leave", id, reason);
    return b.get("leaves", id);
  }

  @Transactional
  public Map<String, Object> review(long id, Map<String, Object> body) {
    write("ADMIN", "TEACHER");
    b.lock();
    Map<String, Object> leave = b.get("leaves", id),
        lesson = b.get("lessons", n(leave.get("lessonId")));
    b.scope.requireTeacherClass(n(lesson.get("classId")));
    if (!(body.get("approved") instanceof Boolean approved))
      throw new BusinessException(400, "approved 必须为布尔值");
    String comment = text(body, "comment", 1000, false);
    String next = approved ? "APPROVED" : "REJECTED";
    if (next.equals(leave.get("status"))) return leave;
    if (!"PENDING".equals(leave.get("status"))) throw new BusinessException(409, "申请已处理，不能重复审批");
    if ("CANCELLED".equals(lesson.get("status"))) throw new BusinessException(409, "课次已停课");
    Map<String, Object> part = b.get("lesson_participants", n(leave.get("participantId")));
    if (!"ACTIVE".equals(part.get("status"))) throw new BusinessException(409, "该学员的原课次安排已取消");
    Map<String, Object> attendance =
        b.db.one("SELECT * FROM attendance WHERE participant_id=#{p.id}", p("id", part.get("id")));
    if (approved && attendance != null && !"LEAVE".equals(attendance.get("status")))
      throw new BusinessException(409, "已经登记出勤或缺勤，请先通过考勤更正处理");
    b.db.update(
        "UPDATE leaves SET"
            + " status=#{p.status},review_comment=#{p.comment},reviewed_by=#{p.user},reviewed_at=CURRENT_TIMESTAMP"
            + " WHERE id=#{p.id}",
        p("status", next, "comment", comment, "user", Actor.current().id(), "id", id));
    if (approved && attendance == null)
      b.db.insert(
          "attendance",
          p(
              "participant_id",
              part.get("id"),
              "status",
              "LEAVE",
              "remark",
              "请假审批通过",
              "recorded_by",
              Actor.current().id()));
    b.audit.log("REVIEW_LEAVE", "leave", id, next + "；" + comment);
    return b.get("leaves", id);
  }

  public List<Map<String, Object>> makeups() {
    return b
        .db
        .list(
            "SELECT m.*,s.name student_name,origin.title original_title,origin.start_time"
                + " original_start_time,target.title target_title,target.start_time"
                + " target_start_time,origin.class_id original_class_id,target.class_id"
                + " target_class_id,op.lesson_id original_lesson_id,oc.name"
                + " original_class_name,tc.name target_class_name FROM makeups m JOIN students s ON"
                + " s.id=m.student_id JOIN lesson_participants op ON"
                + " op.id=m.original_participant_id JOIN lessons origin ON origin.id=op.lesson_id"
                + " JOIN lessons target ON target.id=m.target_lesson_id JOIN classes oc ON"
                + " oc.id=origin.class_id JOIN classes tc ON tc.id=target.class_id ORDER BY m.id"
                + " DESC",
            Map.of())
        .stream()
        .filter(
            r ->
                isStudent()
                    ? n(r.get("studentId")) == n(Actor.current().studentId())
                    : b.scope.canClass(n(r.get("originalClassId")))
                        || b.scope.canClass(n(r.get("targetClassId"))))
        .toList();
  }

  private Map<String, Object> needsMakeup(long participantId) {
    Map<String, Object> original = b.get("lesson_participants", participantId);
    if (!"REGULAR".equals(original.get("kind")) || !"ACTIVE".equals(original.get("status")))
      throw new BusinessException(409, "只能为有效原课次安排补课");
    Map<String, Object> lesson = b.get("lessons", n(original.get("lessonId")));
    if ("CANCELLED".equals(lesson.get("status"))) throw new BusinessException(409, "原课次已停课，无需补课");
    Map<String, Object> att =
        b.db.one("SELECT * FROM attendance WHERE participant_id=#{p.id}", p("id", participantId));
    if (att == null || !Set.of("LEAVE", "ABSENT").contains(att.get("status")))
      throw new BusinessException(409, "原课次需要有请假或缺勤记录");
    if (b.db.count(
            "SELECT COUNT(*) FROM makeups WHERE original_participant_id=#{p.id} AND status IN"
                + " ('ARRANGED','COMPLETED')",
            p("id", participantId))
        > 0) throw new BusinessException(409, "原课次已安排或已完成补课，不能重复关联");
    return original;
  }

  @Transactional
  public Map<String, Object> arrange(Map<String, Object> body) {
    write("ADMIN");
    b.lock();
    long originalId = id(body, "originalParticipantId"), targetId = id(body, "targetLessonId");
    Map<String, Object> original = needsMakeup(originalId),
        source = b.get("lessons", n(original.get("lessonId"))),
        target = b.get("lessons", targetId);
    if (!"SCHEDULED".equals(target.get("status")))
      throw new BusinessException(409, "目标课次必须尚未完成且未停课");
    if (targetId == n(source.get("id"))
        || time(target.get("startTime")).isBefore(time(source.get("endTime"))))
      throw new BusinessException(409, "补课应安排在原课次结束之后");
    Map<String, Object> sourceClass = b.get("classes", n(source.get("classId"))),
        targetClass = b.get("classes", n(target.get("classId")));
    b.activeClass(targetClass);
    if (n(sourceClass.get("courseId")) != n(targetClass.get("courseId")))
      throw new BusinessException(409, "补课必须安排到相同课程的班级");
    long student = n(original.get("studentId"));
    Map<String, Object> old =
        b.db.one(
            "SELECT * FROM lesson_participants WHERE lesson_id=#{p.lesson} AND"
                + " student_id=#{p.student}",
            p("lesson", targetId, "student", student));
    if (old != null
        && ("ACTIVE".equals(old.get("status"))
            || b.db.count(
                    "SELECT COUNT(*) FROM attendance WHERE participant_id=#{p.id}",
                    p("id", old.get("id")))
                > 0)) throw new BusinessException(409, "学员已在目标课次名单中，或已有历史考勤，请选择其他课次");
    Map<String, Object> room = b.get("rooms", n(target.get("roomId")));
    long occupied =
        b.db.count(
            "SELECT COUNT(*) FROM lesson_participants WHERE lesson_id=#{p.id} AND status='ACTIVE'",
            p("id", targetId));
    if (occupied >= Math.min(n(room.get("capacity")), n(targetClass.get("capacity"))))
      throw new BusinessException(409, "目标课次补课名额不足");
    b.assertNoStudentConflict(
        student, time(target.get("startTime")), time(target.get("endTime")), targetId);
    long participant;
    if (old == null)
      participant =
          b.db.insert(
              "lesson_participants",
              p(
                  "lesson_id",
                  targetId,
                  "student_id",
                  student,
                  "kind",
                  "MAKEUP",
                  "status",
                  "ACTIVE",
                  "original_participant_id",
                  originalId));
    else {
      participant = n(old.get("id"));
      b.db.update(
          "UPDATE lesson_participants SET"
              + " status='ACTIVE',kind='MAKEUP',original_participant_id=#{p.origin} WHERE"
              + " id=#{p.id}",
          p("origin", originalId, "id", participant));
    }
    String reason = text(body, "reason", 1000, false);
    long id =
        b.db.insert(
            "makeups",
            p(
                "original_participant_id",
                originalId,
                "target_lesson_id",
                targetId,
                "target_participant_id",
                participant,
                "student_id",
                student,
                "status",
                "ARRANGED",
                "reason",
                reason,
                "created_by",
                Actor.current().id()));
    b.audit.log("ARRANGE_MAKEUP", "makeup", id, reason);
    return b.get("makeups", id);
  }

  @Transactional
  public void cancel(long id, Map<String, Object> body) {
    write("ADMIN");
    b.lock();
    Map<String, Object> makeup = b.get("makeups", id);
    String reason = text(body, "reason", 1000, true);
    if ("CANCELLED".equals(makeup.get("status"))) return;
    if ("COMPLETED".equals(makeup.get("status")))
      throw new BusinessException(409, "补课已完成，须先核对并更正补课考勤，不能直接取消");
    b.db.update(
        "UPDATE makeups SET status='CANCELLED',cancelled_reason=#{p.reason} WHERE id=#{p.id}",
        p("reason", reason, "id", id));
    b.db.update(
        "UPDATE lesson_participants SET status='CANCELLED' WHERE id=#{p.id}",
        p("id", makeup.get("targetParticipantId")));
    b.audit.log("CANCEL_MAKEUP", "makeup", id, reason);
  }

  public List<Map<String, Object>> suggestions() {
    return b
        .db
        .list(
            "SELECT ms.*,s.name student_name,lp.student_id,l.title original_title,l.class_id,t.name"
                + " teacher_name FROM makeup_suggestions ms JOIN lesson_participants lp ON"
                + " lp.id=ms.original_participant_id JOIN lessons l ON l.id=lp.lesson_id JOIN"
                + " students s ON s.id=lp.student_id LEFT JOIN teachers t ON t.id=ms.teacher_id"
                + " ORDER BY ms.id DESC",
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
  public Map<String, Object> suggest(Map<String, Object> body) {
    write("ADMIN", "TEACHER");
    b.lock();
    long participantId = id(body, "originalParticipantId");
    Map<String, Object> part = b.get("lesson_participants", participantId);
    Map<String, Object> lesson = b.get("lessons", n(part.get("lessonId")));
    b.scope.requireTeacherClass(n(lesson.get("classId")));
    needsMakeup(participantId);
    long id =
        b.db.insert(
            "makeup_suggestions",
            p(
                "original_participant_id",
                participantId,
                "comment",
                text(body, "comment", 1000, true),
                "teacher_id",
                Actor.current().teacherId(),
                "created_by",
                Actor.current().id()));
    b.audit.log("SUGGEST_MAKEUP", "makeup_suggestion", id, "为原课次学员记录" + participantId + "提交补课建议");
    return p("id", id);
  }
}
