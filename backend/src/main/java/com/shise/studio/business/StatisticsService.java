package com.shise.studio.business;

import static com.shise.studio.business.BusinessSupport.*;

import com.shise.studio.common.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class StatisticsService {
  private final BusinessSupport b;
  private final ScheduleService schedule;
  private final LearningService learning;
  private final LeaveMakeupService leaveMakeup;

  public StatisticsService(
      BusinessSupport b,
      ScheduleService schedule,
      LearningService learning,
      LeaveMakeupService leaveMakeup) {
    this.b = b;
    this.schedule = schedule;
    this.learning = learning;
    this.leaveMakeup = leaveMakeup;
  }

  /**
   * Every regular participant is one obligation; its completed makeup is credited once to the
   * original.
   */
  public List<Map<String, Object>> progress(Long classId, Long studentId, String from, String to) {
    if (classId != null) b.scope.requireClass(classId);
    if (studentId != null) b.scope.requireStudent(studentId);
    LocalDate fromDate = date(from, null), toDate = date(to, null);
    if (fromDate != null && toDate != null && toDate.isBefore(fromDate))
      throw new BusinessException(400, "结束日期不能早于开始日期");
    String sql =
        "SELECT lp.*,l.class_id,l.start_time,l.end_time,a.status attendance_status,EXISTS(SELECT 1"
            + " FROM makeups m WHERE m.original_participant_id=lp.id AND m.status='COMPLETED')"
            + " makeup_completed FROM lesson_participants lp JOIN lessons l ON l.id=lp.lesson_id"
            + " LEFT JOIN attendance a ON a.participant_id=lp.id WHERE lp.kind='REGULAR' AND"
            + " lp.status='ACTIVE' AND l.status<>'CANCELLED'";
    Map<String, Object> params = p();
    if (classId != null) {
      sql += " AND l.class_id=#{p.classId}";
      params.put("classId", classId);
    }
    if (studentId != null) {
      sql += " AND lp.student_id=#{p.studentId}";
      params.put("studentId", studentId);
    }
    if (fromDate != null) {
      sql += " AND l.start_time>=#{p.from}";
      params.put("from", fromDate.atStartOfDay());
    }
    if (toDate != null) {
      sql += " AND l.start_time<#{p.to}";
      params.put("to", toDate.plusDays(1).atStartOfDay());
    }
    List<Map<String, Object>> parts =
        b.db.list(sql, params).stream()
            .filter(
                r ->
                    isStudent()
                        ? n(r.get("studentId")) == n(Actor.current().studentId())
                        : b.scope.canClass(n(r.get("classId"))))
            .toList();
    Map<Long, Map<String, Object>> output = new LinkedHashMap<>();
    for (Map<String, Object> student :
        b.db.list("SELECT id,name FROM students ORDER BY id", Map.of())) {
      long id = n(student.get("id"));
      if (studentId != null && id != studentId) continue;
      if (!b.scope.canStudent(id)) continue;
      if (classId != null && parts.stream().noneMatch(r -> n(r.get("studentId")) == id)) continue;
      output.put(
          id,
          p(
              "studentId",
              id,
              "studentName",
              student.get("name"),
              "expected",
              0L,
              "completed",
              0L,
              "leaveCount",
              0L,
              "pendingMakeup",
              0L,
              "absentCount",
              0L,
              "occurred",
              0L,
              "completedOccurred",
              0L,
              "makeupCompleted",
              0L,
              "attendanceRate",
              0.0));
    }
    LocalDateTime now = LocalDateTime.now();
    for (Map<String, Object> part : parts) {
      Map<String, Object> row = output.get(n(part.get("studentId")));
      if (row == null) continue;
      increment(row, "expected");
      String att = Objects.toString(part.get("attendanceStatus"), "");
      boolean makeup =
          Boolean.TRUE.equals(part.get("makeupCompleted"))
              || "1".equals(Objects.toString(part.get("makeupCompleted")));
      boolean done = "PRESENT".equals(att) || makeup;
      if (done) increment(row, "completed");
      if (makeup) increment(row, "makeupCompleted");
      if ("LEAVE".equals(att)) increment(row, "leaveCount");
      if ("ABSENT".equals(att)) increment(row, "absentCount");
      if (Set.of("LEAVE", "ABSENT").contains(att) && !done) increment(row, "pendingMakeup");
      if (!time(part.get("endTime")).isAfter(now)) {
        increment(row, "occurred");
        if (done) increment(row, "completedOccurred");
      }
    }
    for (Map<String, Object> row : output.values())
      row.put(
          "attendanceRate",
          n(row.get("occurred")) == 0
              ? 0.0
              : Math.min(
                  100.0,
                  Math.round(n(row.get("completedOccurred")) * 1000.0 / n(row.get("occurred"))) / 10.0));
    return new ArrayList<>(output.values());
  }

  private void increment(Map<String, Object> row, String key) {
    row.put(key, n(row.get(key)) + 1);
  }

  public Map<String, Object> dashboard() {
    List<Map<String, Object>> progress = progress(null, null, null, null),
        lessons = schedule.lessons(null, null, null),
        works = learning.works(),
        leaves = leaveMakeup.leaves();
    long classes =
        b.db.list("SELECT id FROM classes", Map.of()).stream()
            .filter(r -> b.scope.canClass(n(r.get("id"))))
            .count();
    long pending = progress.stream().mapToLong(r -> n(r.get("pendingMakeup"))).sum(),
        occurred = progress.stream().mapToLong(r -> n(r.get("occurred"))).sum(),
        completed = progress.stream().mapToLong(r -> n(r.get("completedOccurred"))).sum();
    List<Map<String, Object>> upcoming =
        lessons.stream()
            .filter(
                r ->
                    !"CANCELLED".equals(r.get("status"))
                        && !time(r.get("endTime")).isBefore(LocalDateTime.now()))
            .sorted(Comparator.comparing(r -> time(r.get("startTime"))))
            .limit(6)
            .toList();
    return p(
        "studentCount",
        progress.size(),
        "classCount",
        classes,
        "lessonCount",
        lessons.stream().filter(r -> !"CANCELLED".equals(r.get("status"))).count(),
        "pendingLeaveCount",
        leaves.stream().filter(r -> "PENDING".equals(r.get("status"))).count(),
        "pendingMakeupCount",
        pending,
        "workCount",
        works.size(),
        "attendanceRate",
        occurred == 0 ? 0.0 : Math.min(100.0, Math.round(completed * 1000.0 / occurred) / 10.0),
        "upcomingLessons",
        upcoming,
        "recentWorks",
        works.stream().limit(6).toList(),
        "progress",
        progress);
  }
}
