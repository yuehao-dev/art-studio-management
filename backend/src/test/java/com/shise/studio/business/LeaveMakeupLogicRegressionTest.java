package com.shise.studio.business;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.shise.studio.common.*;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.*;

class LeaveMakeupLogicRegressionTest {
  Db db;
  Scope scope;
  Audit audit;
  BusinessSupport support;
  StatisticsService statistics;
  LeaveMakeupService leaveMakeup;

  @BeforeEach
  void setup() {
    db = mock(Db.class);
    scope = mock(Scope.class);
    audit = mock(Audit.class);
    support = new BusinessSupport(db, scope, audit);
    leaveMakeup = new LeaveMakeupService(support);
    var schedule = mock(ScheduleService.class);
    var learning = mock(LearningService.class);
    var leaves = mock(LeaveMakeupService.class);
    statistics = new StatisticsService(support, schedule, learning, leaves);
    Actor.set(new Actor.User(1, "admin", "管理员", "ADMIN", null, null, false));
    when(scope.canStudent(anyLong())).thenReturn(true);
    when(scope.canClass(anyLong())).thenReturn(true);
    when(db.list(eq("SELECT id,name FROM students ORDER BY id"), anyMap()))
        .thenReturn(List.of(Map.of("id", 7L, "name", "统计测试学员")));
    when(schedule.lessons(null, null, null)).thenReturn(List.of());
    when(learning.works()).thenReturn(List.of());
    when(leaves.leaves()).thenReturn(List.of());
  }

  @AfterEach
  void clearActor() {
    Actor.clear();
  }

  Map<String, Object> participant(String attendance, LocalDateTime end, boolean makeup) {
    return Map.of("studentId", 7L, "classId", 1L, "attendanceStatus", attendance,
        "endTime", end, "makeupCompleted", makeup);
  }

  @Test
  void ongoingCourseRemainsOnHomeAfterAttendanceIsComplete() {
    var schedule = mock(ScheduleService.class);
    var learning = mock(LearningService.class);
    var leaves = mock(LeaveMakeupService.class);
    var ongoing = Map.<String, Object>of("id", 8L, "status", "COMPLETED",
        "startTime", LocalDateTime.now().minusMinutes(10), "endTime", LocalDateTime.now().plusMinutes(50));
    var cancelled = Map.<String, Object>of("id", 9L, "status", "CANCELLED",
        "startTime", LocalDateTime.now().plusDays(1), "endTime", LocalDateTime.now().plusDays(1).plusHours(1));
    when(schedule.lessons(null, null, null)).thenReturn(List.of(ongoing, cancelled));
    var home = new StatisticsService(support, schedule, learning, leaves).dashboard();
    assertEquals(List.of(ongoing), home.get("upcomingLessons"));
  }

  @Test
  void ongoingAttendanceCannotInflateTheRateForEndedLessons() {
    when(db.list(startsWith("SELECT lp.*"), anyMap())).thenReturn(List.of(
        participant("ABSENT", LocalDateTime.now().minusDays(1), false),
        participant("PRESENT", LocalDateTime.now().plusHours(1), false)));
    var result = statistics.progress(null, null, null, null).get(0);
    assertEquals(2L, result.get("expected"));
    assertEquals(1L, result.get("completed"));
    assertEquals(1L, result.get("occurred"));
    assertEquals(0L, result.get("completedOccurred"));
    assertEquals(0.0, result.get("attendanceRate"));
    assertEquals(0.0, statistics.dashboard().get("attendanceRate"));
  }

  @Test
  void completedMakeupFulfillsOneEndedObligationOnly() {
    when(db.list(startsWith("SELECT lp.*"), anyMap())).thenReturn(List.of(
        participant("LEAVE", LocalDateTime.now().minusDays(2), true),
        participant("ABSENT", LocalDateTime.now().minusDays(1), false)));
    var result = statistics.progress(null, null, null, null).get(0);
    assertEquals(1L, result.get("completed"));
    assertEquals(1L, result.get("completedOccurred"));
    assertEquals(1L, result.get("makeupCompleted"));
    assertEquals(1L, result.get("pendingMakeup"));
    assertEquals(50.0, result.get("attendanceRate"));
  }

  @Test
  void noEndedLessonsHaveZeroRateEvenAfterCurrentAttendance() {
    when(db.list(startsWith("SELECT lp.*"), anyMap())).thenReturn(List.of(
        participant("PRESENT", LocalDateTime.now().plusHours(1), false)));
    var result = statistics.progress(null, null, null, null).get(0);
    assertEquals(0L, result.get("occurred"));
    assertEquals(0.0, result.get("attendanceRate"));
  }

  @Test
  void reversedStatisticsDateRangeIsRejected() {
    assertEquals(400, assertThrows(BusinessException.class,
        () -> statistics.progress(null, null, "2026-12-01", "2026-11-01")).getStatus());
    verify(db, never()).list(startsWith("SELECT lp.*"), anyMap());
  }

  void pendingLeave(Map<String, Object> attendance) {
    Map<String, Object> leave = new HashMap<>(Map.of("id", 3L, "lessonId", 4L,
        "participantId", 5L, "status", "PENDING"));
    when(db.one(eq("SELECT * FROM leaves WHERE id=#{p.id}"), anyMap())).thenReturn(leave);
    when(db.one(eq("SELECT * FROM lessons WHERE id=#{p.id}"), anyMap()))
        .thenReturn(Map.of("id", 4L, "classId", 1L, "status", "SCHEDULED",
            "startTime", LocalDateTime.now().minusDays(1)));
    when(db.one(eq("SELECT * FROM lesson_participants WHERE id=#{p.id}"), anyMap()))
        .thenReturn(Map.of("id", 5L, "status", "ACTIVE"));
    when(db.one(eq("SELECT * FROM attendance WHERE participant_id=#{p.id}"), anyMap()))
        .thenReturn(attendance);
    when(db.update(startsWith("UPDATE leaves SET"), anyMap())).thenAnswer(invocation -> {
      Map<String, Object> parameters = invocation.getArgument(1);
      leave.put("status", parameters.get("status"));
      return 1;
    });
  }

  @Test
  void pendingLeaveMayBeProcessedLateBeforeActualAttendanceIsRecorded() {
    pendingLeave(null);
    assertEquals("APPROVED", leaveMakeup.review(3, Map.of("approved", true)).get("status"));
    verify(db).insert(eq("attendance"), argThat(p -> "LEAVE".equals(p.get("status"))));
  }

  @Test
  void lateApprovalCannotOverwriteActualAttendance() {
    pendingLeave(Map.of("status", "PRESENT"));
    assertEquals(409, assertThrows(BusinessException.class,
        () -> leaveMakeup.review(3, Map.of("approved", true))).getStatus());
    verify(db, never()).update(startsWith("UPDATE leaves SET"), anyMap());
    verify(db, never()).insert(eq("attendance"), anyMap());
  }

  @Test
  void suggestionChecksTeacherScopeBeforeDisclosingMakeupEligibility() {
    Actor.set(new Actor.User(2, "teacher", "教师", "TEACHER", null, 1L, false));
    when(db.one(eq("SELECT * FROM lesson_participants WHERE id=#{p.id}"), anyMap()))
        .thenReturn(Map.of("id", 5L, "lessonId", 4L, "kind", "REGULAR", "status", "ACTIVE"));
    when(db.one(eq("SELECT * FROM lessons WHERE id=#{p.id}"), anyMap()))
        .thenReturn(Map.of("id", 4L, "classId", 9L));
    doThrow(new BusinessException(403, "不能操作此班级"))
        .when(scope).requireTeacherClass(9L);
    assertEquals(403, assertThrows(BusinessException.class,
        () -> leaveMakeup.suggest(Map.of("originalParticipantId", 5L, "comment", "建议"))).getStatus());
    verify(db, never()).one(startsWith("SELECT * FROM attendance"), anyMap());
  }
}
