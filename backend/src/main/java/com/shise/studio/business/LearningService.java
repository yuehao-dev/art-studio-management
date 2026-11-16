package com.shise.studio.business;

import static com.shise.studio.business.BusinessSupport.*;

import com.shise.studio.common.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LearningService {
  private final BusinessSupport b;

  public LearningService(BusinessSupport b) {
    this.b = b;
  }

  public List<Map<String, Object>> tasks() {
    return b
        .db
        .list(
            "SELECT t.*,c.name class_name,l.title lesson_title,(SELECT COUNT(*) FROM works w WHERE"
                + " w.task_id=t.id) submission_count FROM tasks t JOIN classes c ON c.id=t.class_id"
                + " LEFT JOIN lessons l ON l.id=t.lesson_id ORDER BY t.id DESC",
            Map.of())
        .stream()
        .filter(r -> b.scope.canClass(n(r.get("classId"))))
        .map(this::withSubmissionFlag)
        .toList();
  }

  @Transactional
  public Map<String, Object> task(Map<String, Object> body) {
    write("ADMIN", "TEACHER");
    b.lock();
    long classId = id(body, "classId");
    b.scope.requireTeacherClass(classId);
    b.activeClass(b.get("classes", classId));
    Long lessonId = n(body.get("lessonId")) == 0 ? null : n(body.get("lessonId"));
    if (lessonId != null) {
      Map<String, Object> lesson = b.get("lessons", lessonId);
      if (n(lesson.get("classId")) != classId || "CANCELLED".equals(lesson.get("status")))
        throw new BusinessException(400, "请选择本班的有效课次");
    }
    long id =
        b.db.insert(
            "tasks",
            p(
                "class_id",
                classId,
                "lesson_id",
                lessonId,
                "title",
                text(body, "title", 200, true),
                "description",
                text(body, "description", 10000, false),
                "due_date",
                date(body.get("dueDate"), null),
                "created_by",
                Actor.current().id()));
    b.audit.log("CREATE_TASK", "task", id, "发布教学任务");
    return b.get("tasks", id);
  }

  @Transactional
  public Map<String, Object> updateTask(long taskId, Map<String, Object> body) {
    write("ADMIN", "TEACHER");
    b.lock();
    Map<String, Object> existing = b.get("tasks", taskId);
    b.scope.requireTeacherClass(n(existing.get("classId")));
    if ((body.containsKey("classId") && n(body.get("classId")) != n(existing.get("classId")))
        || (body.containsKey("lessonId") && n(body.get("lessonId")) != n(existing.get("lessonId"))))
      throw new BusinessException(400, "任务的所属班级和关联课次不能修改，请新建任务");
    Map<String, Object> merged = new LinkedHashMap<>(existing);
    merged.putAll(body);
    b.db.update("UPDATE tasks SET title=#{p.title},description=#{p.description},due_date=#{p.dueDate}"
            + " WHERE id=#{p.id}",
        p("id", taskId, "title", text(merged, "title", 200, true),
            "description", text(merged, "description", 10000, false),
            "dueDate", date(merged.get("dueDate"), null)));
    b.audit.log("UPDATE_TASK", "task", taskId, "修改任务说明或截止日期，保留原班级与课次");
    return b.get("tasks", taskId);
  }

  public List<Map<String, Object>> works() {
    return b
        .db
        .list(
            "SELECT w.*,s.name student_name,t.title task_title,t.class_id,t.due_date,c.name class_name,(SELECT"
                + " COUNT(*) FROM work_versions v WHERE v.work_id=w.id) version_count,(SELECT"
                + " MAX(v.version_no) FROM work_versions v WHERE v.work_id=w.id)"
                + " latest_version,(SELECT v.file_id FROM work_versions v WHERE v.work_id=w.id"
                + " ORDER BY v.version_no DESC LIMIT 1) latest_file_id,(SELECT MAX(v.created_at)"
                + " FROM work_versions v WHERE v.work_id=w.id) updated_at,(SELECT COUNT(*) FROM"
                + " reviews r JOIN work_versions v ON v.id=r.version_id WHERE v.work_id=w.id)"
                + " review_count FROM works w JOIN students s ON s.id=w.student_id JOIN tasks t ON"
                + " t.id=w.task_id JOIN classes c ON c.id=t.class_id ORDER BY w.id DESC",
            Map.of())
        .stream()
        .filter(
            r ->
                isStudent()
                    ? n(r.get("studentId")) == n(Actor.current().studentId())
                    : b.scope.canClass(n(r.get("classId"))))
        .map(this::withSubmissionFlag)
        .toList();
  }

  private Map<String, Object> workScope(long id) {
    Map<String, Object> work = b.get("works", id), task = b.get("tasks", n(work.get("taskId")));
    if (isStudent()) {
      if (n(work.get("studentId")) != n(Actor.current().studentId()))
        throw new BusinessException(403, "只能查看本人作品");
    } else b.scope.requireTeacherClass(n(task.get("classId")));
    return work;
  }

  @Transactional
  public Map<String, Object> submit(Map<String, Object> body) {
    write("STUDENT");
    b.lock();
    long taskId = id(body, "taskId"), fileId = id(body, "fileId");
    Map<String, Object> task = b.get("tasks", taskId);
    b.scope.requireCurrentEnrollment(n(task.get("classId")));
    submissionOpen(task);
    b.ownedFile(fileId);
    if (b.db.count(
            "SELECT COUNT(*) FROM works WHERE task_id=#{p.task} AND student_id=#{p.student}",
            p("task", taskId, "student", Actor.current().studentId()))
        > 0) throw new BusinessException(409, "该任务已有作品，请在原作品上提交新版本");
    long id =
        b.db.insert(
            "works",
            p(
                "task_id",
                taskId,
                "student_id",
                Actor.current().studentId(),
                "title",
                text(body, "title", 200, true)));
    long version =
        b.db.insert(
            "work_versions",
            p(
                "work_id",
                id,
                "file_id",
                fileId,
                "version_no",
                1,
                "description",
                text(body, "description", 10000, false)));
    b.audit.log("SUBMIT_WORK", "work", id, "提交第1版");
    return p("id", id, "versionId", version, "versionNo", 1);
  }

  public List<Map<String, Object>> versions(long workId) {
    workScope(workId);
    List<Map<String, Object>> rows =
        b.db.list(
            "SELECT v.*,f.original_name,f.content_type,f.size FROM work_versions v JOIN files f ON"
                + " f.id=v.file_id WHERE v.work_id=#{p.id} ORDER BY v.version_no DESC",
            p("id", workId));
    for (Map<String, Object> row : rows)
      row.put(
          "reviews",
          b.db.list(
              "SELECT r.*,COALESCE(t.name,u.display_name) teacher_name FROM reviews r LEFT JOIN"
                  + " teachers t ON t.id=r.teacher_id JOIN users u ON u.id=r.created_by WHERE"
                  + " r.version_id=#{p.id} ORDER BY r.id DESC",
              p("id", row.get("id"))));
    return rows;
  }

  @Transactional
  public Map<String, Object> version(long workId, Map<String, Object> body) {
    write("STUDENT");
    b.lock();
    Map<String, Object> work = workScope(workId);
    if (n(work.get("studentId")) != n(Actor.current().studentId()))
      throw new BusinessException(403, "只能修改本人作品");
    Map<String, Object> task = b.get("tasks", n(work.get("taskId")));
    b.scope.requireCurrentEnrollment(n(task.get("classId")));
    submissionOpen(task);
    long fileId = id(body, "fileId");
    b.ownedFile(fileId);
    long next =
        b.db.count(
            "SELECT COALESCE(MAX(version_no),0)+1 FROM work_versions WHERE work_id=#{p.id}",
            p("id", workId));
    long id =
        b.db.insert(
            "work_versions",
            p(
                "work_id",
                workId,
                "file_id",
                fileId,
                "version_no",
                next,
                "description",
                text(body, "description", 10000, false)));
    b.audit.log("NEW_WORK_VERSION", "work", workId, "提交第" + next + "版");
    return p("id", id, "versionNo", next);
  }

  private void submissionOpen(Map<String, Object> task) {
    LocalDate due = date(task.get("dueDate"), null);
    if (due != null && due.isBefore(LocalDate.now(ZoneId.of("Asia/Shanghai"))))
      throw new BusinessException(409, "任务已截止，不能继续提交作品或新版本");
  }

  private Map<String, Object> withSubmissionFlag(Map<String, Object> row) {
    Map<String, Object> result = new LinkedHashMap<>(row);
    LocalDate due = date(row.get("dueDate"), null);
    boolean allowed = isStudent() && !Actor.current().readOnly()
        && b.scope.hasCurrentEnrollment(n(row.get("classId")))
        && (due == null || !due.isBefore(LocalDate.now(ZoneId.of("Asia/Shanghai"))));
    result.put("submissionAllowed", allowed);
    return result;
  }

  @Transactional
  public Map<String, Object> review(long workId, Map<String, Object> body) {
    write("ADMIN", "TEACHER");
    workScope(workId);
    long versionId = id(body, "versionId");
    Map<String, Object> version = b.get("work_versions", versionId);
    if (n(version.get("workId")) != workId) throw new BusinessException(400, "点评版本不属于当前作品");
    Integer score = null;
    if (body.get("score") != null && !body.get("score").toString().isBlank()) {
      long v = n(body.get("score"));
      if (v < 0 || v > 100) throw new BusinessException(400, "评分应在0至100之间");
      score = (int) v;
    }
    long id =
        b.db.insert(
            "reviews",
            p(
                "version_id",
                versionId,
                "teacher_id",
                Actor.current().teacherId(),
                "content",
                text(body, "content", 10000, true),
                "score",
                score,
                "created_by",
                Actor.current().id()));
    b.audit.log("REVIEW_WORK", "work", workId, "点评版本 " + version.get("versionNo"));
    return p("id", id);
  }
}
