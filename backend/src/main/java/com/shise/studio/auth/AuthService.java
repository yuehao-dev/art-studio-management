package com.shise.studio.auth;

import com.shise.studio.common.*;
import io.jsonwebtoken.Claims;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
  private final Db db;
  private final JwtService jwt;
  private final LoginLimiter limiter;
  private final Audit audit;
  private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

  public AuthService(Db db, JwtService jwt, LoginLimiter limiter, Audit audit) {
    this.db = db;
    this.jwt = jwt;
    this.limiter = limiter;
    this.audit = audit;
  }

  public Map<String, Object> login(Map<String, Object> p, String ip) {
    String username = Input.text(p, "username", 64, true),
        password = Input.text(p, "password", 128, true);
    // 与账户创建规则一致，避免数据库的不区分重音匹配产生限流键别名。
    if (!username.matches("[a-zA-Z0-9_.-]{3,64}"))
      throw new BusinessException(401, "用户名或密码不正确，或账户已停用");
    String limitKey = limiter.key(username, ip);
    limiter.check(limitKey);
    var row =
        db.one("SELECT * FROM users WHERE username=#{p.username}", Map.of("username", username));
    if (row == null
        || !Input.bool(row.get("enabled"))
        || !encoder.matches(password, String.valueOf(row.get("passwordHash")))) {
      limiter.fail(limitKey);
      throw new BusinessException(401, "用户名或密码不正确，或账户已停用");
    }
    limiter.clear(limitKey);
    var user = toUser(row);
    Actor.set(user);
    audit.log("LOGIN", "USER", user.id(), "用户登录");
    return Map.of(
        "token", jwt.access(user.id(), ((Number) row.get("authVersion")).intValue()), "user", user);
  }

  public Actor.User authenticate(Claims c) {
    long uid;
    try {
      uid = Long.parseLong(c.getSubject());
    } catch (Exception e) {
      throw new BusinessException(401, "授权无效");
    }
    var row = db.one("SELECT * FROM users WHERE id=#{p.id}", Map.of("id", uid));
    if (row == null
        || !Input.bool(row.get("enabled"))
        || ((Number) row.get("authVersion")).intValue() != ((Number) c.get("av")).intValue())
      throw new BusinessException(401, "登录已失效，请重新登录");
    return toUser(row);
  }

  public Actor.User fromAccess(String token) {
    return authenticate(jwt.parse(token, "access"));
  }

  public int version(long id) {
    var r = db.one("SELECT auth_version FROM users WHERE id=#{p.id}", Map.of("id", id));
    return ((Number) r.get("authVersion")).intValue();
  }

  @Transactional
  public void logout() {
    long id = Actor.current().id();
    db.update("UPDATE users SET auth_version=auth_version+1 WHERE id=#{p.id}", Map.of("id", id));
    audit.log("LOGOUT", "USER", id, "撤销登录会话");
  }

  @Transactional
  public void password(Map<String, Object> p) {
    String old = Input.text(p, "oldPassword", 128, true),
        next = Input.text(p, "newPassword", 128, true);
    validatePassword(next);
    long id = Actor.current().id();
    var row =
        db.one("SELECT password_hash FROM users WHERE id=#{p.id} FOR UPDATE", Map.of("id", id));
    if (!encoder.matches(old, String.valueOf(row.get("passwordHash"))))
      throw new BusinessException(400, "原密码不正确");
    db.update(
        "UPDATE users SET password_hash=#{p.hash},auth_version=auth_version+1 WHERE id=#{p.id}",
        Map.of("hash", encoder.encode(next), "id", id));
    audit.log("PASSWORD_CHANGE", "USER", id, "用户修改密码");
  }

  public String hash(String password) {
    validatePassword(password);
    return encoder.encode(password);
  }

  public static void validatePassword(String s) {
    if (s == null || s.length() < 8 || s.getBytes(StandardCharsets.UTF_8).length > 72)
      throw new BusinessException(400, "密码至少8位，UTF-8长度不超过72字节");
  }

  private Actor.User toUser(Map<String, Object> r) {
    return new Actor.User(
        Input.number(r.get("id")),
        String.valueOf(r.get("username")),
        String.valueOf(r.get("displayName")),
        String.valueOf(r.get("role")),
        r.get("studentId") == null ? null : Input.number(r.get("studentId")),
        r.get("teacherId") == null ? null : Input.number(r.get("teacherId")),
        Input.bool(r.get("readOnly")));
  }
}
