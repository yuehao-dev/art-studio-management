package com.shise.studio;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.shise.studio.auth.*;
import com.shise.studio.common.*;
import com.shise.studio.files.FileService;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockMultipartFile;

/** 聚焦越权与会话撤销，不依赖外部数据库。完整业务回归另以实际MySQL运行。 */
class SecurityRegressionTest {
  Db db;
  Scope scope;
  Audit audit;
  JwtService jwt;
  AuthService auth;

  @BeforeEach
  void setup() {
    db = mock(Db.class);
    scope = mock(Scope.class);
    audit = mock(Audit.class);
    jwt = new JwtService("unit-test-key-at-least-32-characters-long", new MockEnvironment());
    auth = new AuthService(db, jwt, mock(LoginLimiter.class), audit);
  }

  @AfterEach
  void cleanup() {
    Actor.clear();
  }

  private Actor.User student(long sid, boolean ro) {
    return new Actor.User(3, "student", "学员", "STUDENT", sid, null, ro);
  }

  @Test
  void guardianCannotMutate() {
    Actor.set(student(1, true));
    assertEquals(403, assertThrows(BusinessException.class, Actor::requireWritable).getStatus());
  }

  @Test
  void guardianScopeIsBoundStudent() {
    Actor.set(student(1, true));
    Scope real = new Scope(db);
    assertTrue(real.canStudent(1));
    assertFalse(real.canStudent(2));
  }

  @Test
  void accessTokensCannotAuthorizeDirectFiles() {
    String token = jwt.access(3, 0);
    assertEquals(
        401, assertThrows(BusinessException.class, () -> jwt.parse(token, "file")).getStatus());
  }

  @Test
  void modifiedTokenFailsSignature() {
    String token = jwt.access(3, 0);
    String[] parts = token.split("\\.");
    String bad =
        parts[0]
            + "."
            + parts[1]
            + "."
            + (parts[2].startsWith("A") ? "B" : "A")
            + parts[2].substring(1);
    assertThrows(BusinessException.class, () -> jwt.parse(bad, "access"));
  }

  @Test
  void accountVersionChangeRevokesPreviouslyIssuedToken() {
    var row = new HashMap<String, Object>();
    row.put("id", 3L);
    row.put("enabled", true);
    row.put("authVersion", 1);
    when(db.one(anyString(), anyMap())).thenReturn(row);
    assertEquals(
        401,
        assertThrows(BusinessException.class, () -> auth.fromAccess(jwt.access(3, 0))).getStatus());
  }

  @Test
  void disabledAccountCannotUseExistingToken() {
    when(db.one(anyString(), anyMap()))
        .thenReturn(Map.of("id", 3L, "enabled", false, "authVersion", 0));
    assertEquals(
        401,
        assertThrows(BusinessException.class, () -> auth.fromAccess(jwt.access(3, 0))).getStatus());
  }

  private FileService fileService() {
    return new FileService(
        db,
        scope,
        audit,
        jwt,
        auth,
        "local",
        "target/test-uploads",
        "https://oss-cn-beijing.aliyuncs.com",
        "unused",
        "",
        "");
  }

  @Test
  void peerStudentCannotReadPrivateWorkFile() {
    Actor.set(student(1, false));
    when(db.one(anyString(), anyMap())).thenReturn(Map.of("id", 8L, "ownerId", 99L));
    when(db.list(startsWith("SELECT class_id"), anyMap())).thenReturn(List.of());
    when(db.list(startsWith("SELECT w.student_id"), anyMap()))
        .thenReturn(List.of(Map.of("studentId", 2L, "classId", 1L)));
    assertEquals(
        403, assertThrows(BusinessException.class, () -> fileService().authorized(8)).getStatus());
  }

  @Test
  void guardianCanReadBoundStudentWorkFile() {
    Actor.set(student(1, true));
    when(db.one(anyString(), anyMap())).thenReturn(Map.of("id", 8L, "ownerId", 99L));
    when(db.list(startsWith("SELECT class_id"), anyMap())).thenReturn(List.of());
    when(db.list(startsWith("SELECT w.student_id"), anyMap()))
        .thenReturn(List.of(Map.of("studentId", 1L, "classId", 1L)));
    assertEquals(8L, fileService().authorized(8).get("id"));
  }

  @Test
  void forgedImageExtensionIsRejected() {
    Actor.set(student(1, false));
    var file =
        new MockMultipartFile(
            "file", "picture.png", "image/png", "<script>bad()</script>".getBytes());
    assertEquals(
        400, assertThrows(BusinessException.class, () -> fileService().upload(file)).getStatus());
    verify(db, never()).insert(anyString(), anyMap());
  }

  @Test
  void bcryptLengthLimitIsValidatedInBytes() {
    assertThrows(BusinessException.class, () -> AuthService.validatePassword("画".repeat(25)));
  }
}
