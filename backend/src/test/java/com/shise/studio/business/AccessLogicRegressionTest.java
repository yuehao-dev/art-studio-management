package com.shise.studio.business;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.shise.studio.auth.*;
import com.shise.studio.catalog.CatalogService;
import com.shise.studio.common.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.core.StringRedisTemplate;

class AccessLogicRegressionTest {
  private Db db;
  private Scope scope;
  private Audit audit;
  private AuthService auth;
  private LearningService learning;

  @BeforeEach
  void setup() {
    db = mock(Db.class);
    scope = mock(Scope.class);
    audit = mock(Audit.class);
    auth = mock(AuthService.class);
    learning = new LearningService(new BusinessSupport(db, scope, audit));
    Actor.set(new Actor.User(3, "student", "学员", "STUDENT", 1L, null, false));
  }

  @AfterEach
  void cleanup() { Actor.clear(); }

  @Test
  void usernameCaseCannotBypassLoginLimit() {
    var limiter = new LoginLimiter(mock(StringRedisTemplate.class), false);
    for (int i = 0; i < 10; i++) limiter.fail(limiter.key("admin", "127.0.0.1"));
    assertEquals(429, assertThrows(BusinessException.class,
        () -> limiter.check(limiter.key("AdMiN", "127.0.0.1"))).getStatus());
    assertDoesNotThrow(() -> limiter.check(limiter.key("teacher", "127.0.0.1")));
  }

  @Test
  void accentedLoginAliasCannotUseDatabaseCollationToBypassLimit() {
    var service = new AuthService(db, mock(JwtService.class), mock(LoginLimiter.class), audit);
    assertEquals(401, assertThrows(BusinessException.class,
        () -> service.login(Map.of("username", "ádmin", "password", "invalid"), "127.0.0.1")).getStatus());
    verify(db, never()).one(anyString(), anyMap());
  }

  private Map<String, Object> task(LocalDate due) {
    Map<String, Object> result = new HashMap<>(Map.of("id", 8L, "classId", 1L, "title", "任务"));
    result.put("dueDate", due);
    return result;
  }

  private void setupTask(LocalDate due) {
    when(db.one(startsWith("SELECT * FROM tasks"), anyMap())).thenReturn(task(due));
    when(db.one(startsWith("SELECT * FROM works"), anyMap()))
        .thenReturn(Map.of("id", 7L, "taskId", 8L, "studentId", 1L));
    when(db.one(startsWith("SELECT * FROM files"), anyMap()))
        .thenReturn(Map.of("id", 9L, "ownerId", 3L));
  }

  @Test
  void expiredTaskRejectsFirstSubmissionBeforeAnyWrite() {
    setupTask(LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(1));
    assertEquals(409, assertThrows(BusinessException.class,
        () -> learning.submit(Map.of("taskId", 8L, "fileId", 9L, "title", "作品"))).getStatus());
    verify(db, never()).insert(anyString(), anyMap());
  }

  @Test
  void expiredTaskAlsoRejectsNewVersions() {
    setupTask(LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(1));
    assertEquals(409, assertThrows(BusinessException.class,
        () -> learning.version(7, Map.of("fileId", 9L))).getStatus());
    verify(db, never()).insert(anyString(), anyMap());
  }

  @Test
  void deadlineTodayStillAcceptsSubmission() {
    setupTask(LocalDate.now(ZoneId.of("Asia/Shanghai")));
    when(db.insert(eq("works"), anyMap())).thenReturn(7L);
    when(db.insert(eq("work_versions"), anyMap())).thenReturn(10L);
    assertEquals(7L, learning.submit(Map.of("taskId", 8L, "fileId", 9L, "title", "作品")).get("id"));
    verify(scope).requireCurrentEnrollment(1L);
  }

  @Test
  void expiredTaskKeepsHistoricalVersionsReadable() {
    setupTask(LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(1));
    Map<String, Object> version = new HashMap<>(Map.of("id", 10L, "versionNo", 1));
    when(db.list(startsWith("SELECT v.*"), anyMap())).thenReturn(List.of(version));
    assertEquals(1, learning.versions(7).size());
    verify(scope, never()).requireCurrentEnrollment(anyLong());
  }

  @Test
  void taskListExposesSubmissionEligibilityForTheCurrentAccount() {
    when(scope.canClass(1L)).thenReturn(true);
    when(scope.hasCurrentEnrollment(1L)).thenReturn(true);
    when(db.list(startsWith("SELECT t.*"), anyMap())).thenReturn(List.of(
        task(LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(1)), task(null)));
    assertEquals(List.of(false, true), learning.tasks().stream()
        .map(t -> t.get("submissionAllowed")).toList());
    Actor.set(new Actor.User(4, "guardian", "监护人", "STUDENT", 1L, null, true));
    assertTrue(learning.tasks().stream().allMatch(t -> Boolean.FALSE.equals(t.get("submissionAllowed"))));
  }

  @Test
  void teacherCanExtendDeadlineWithoutMovingTheTask() {
    Actor.set(new Actor.User(2, "teacher", "教师", "TEACHER", null, 1L, false));
    setupTask(LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(1));
    LocalDate tomorrow = LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(1);
    learning.updateTask(8, Map.of("dueDate", tomorrow.toString()));
    verify(scope).requireTeacherClass(1L);
    verify(db).update(startsWith("UPDATE tasks SET title="), argThat(p ->
        tomorrow.equals(p.get("dueDate")) && "任务".equals(p.get("title"))));
    verify(audit).log(eq("UPDATE_TASK"), eq("task"), eq(8L), anyString());
  }

  @Test
  void taskEditCannotMoveExistingWorksToAnotherClassOrLesson() {
    Actor.set(new Actor.User(2, "teacher", "教师", "TEACHER", null, 1L, false));
    setupTask(null);
    assertEquals(400, assertThrows(BusinessException.class,
        () -> learning.updateTask(8, Map.of("classId", 2L))).getStatus());
    assertEquals(400, assertThrows(BusinessException.class,
        () -> learning.updateTask(8, Map.of("lessonId", 9L))).getStatus());
    verify(db, never()).update(anyString(), anyMap());
  }

  private CatalogService catalogAsAdmin() {
    Actor.set(new Actor.User(10, "admin", "管理员", "ADMIN", null, null, false));
    return new CatalogService(db, scope, audit, auth);
  }

  @Test
  void concurrentPrivilegeLossRejectsStaleAdministratorAction() {
    var catalog = catalogAsAdmin();
    when(db.one(startsWith("SELECT role,enabled"), anyMap()))
        .thenReturn(Map.of("role", "ADMIN", "enabled", false));
    assertEquals(403, assertThrows(BusinessException.class,
        () -> catalog.updateUser(11, Map.of("enabled", false))).getStatus());
    verify(db, never()).update(anyString(), anyMap());
  }

  @Test
  void administratorCannotDisableTheirOwnAccount() {
    var catalog = catalogAsAdmin();
    when(db.one(startsWith("SELECT role,enabled"), anyMap()))
        .thenReturn(Map.of("role", "ADMIN", "enabled", true));
    when(db.one(startsWith("SELECT * FROM users"), anyMap()))
        .thenReturn(Map.of("id", 10L, "username", "admin", "displayName", "管理员",
            "role", "ADMIN", "enabled", true));
    assertEquals(400, assertThrows(BusinessException.class,
        () -> catalog.updateUser(10, Map.of("enabled", false))).getStatus());
    verify(db, never()).update(anyString(), anyMap());
  }

  @Test
  void studentNameLengthMatchesDatabaseColumn() {
    var catalog = catalogAsAdmin();
    assertEquals(400, assertThrows(BusinessException.class,
        () -> catalog.create("students", Map.of("name", "学".repeat(81)))).getStatus());
    verify(db, never()).insert(anyString(), anyMap());
  }
}
