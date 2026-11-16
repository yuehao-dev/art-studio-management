package com.shise.studio.catalog;

import com.shise.studio.auth.AuthService;
import com.shise.studio.business.EnrollmentPeriods;
import com.shise.studio.common.*;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CatalogService {
  private static final Set<String> TYPES =
      Set.of("students", "teachers", "courses", "classes", "rooms");
  private final Db db;
  private final Scope scope;
  private final Audit audit;
  private final AuthService auth;

  public CatalogService(Db db, Scope scope, Audit audit, AuthService auth) {
    this.db = db;
    this.scope = scope;
    this.audit = audit;
    this.auth = auth;
  }

  private void type(String type) {
    if (!TYPES.contains(type)) throw new BusinessException(404, "资源不存在");
  }

  public List<Map<String, Object>> list(String type) {
    type(type);
    String sql =
        switch (type) {
          case "classes" ->
              "SELECT c.*,co.name course_name,t.name teacher_name,(SELECT COUNT(DISTINCT e.student_id) FROM enrollments"
                  + " e WHERE e.class_id=c.id AND e.effective_date<=#{p.today}"
                  + " AND (e.end_date IS NULL OR e.end_date>#{p.today})) student_count FROM classes c"
                  + " JOIN courses co ON co.id=c.course_id JOIN teachers t ON t.id=c.teacher_id"
                  + " ORDER BY c.id DESC";
          default -> "SELECT * FROM " + type + " ORDER BY id DESC";
        };
    var all = db.list(sql, type.equals("classes")
        ? Map.of("today", LocalDate.now(ZoneId.of("Asia/Shanghai"))) : Map.of());
    var a = Actor.current();
    if (a.role().equals("ADMIN")) return all;
    return all.stream()
        .filter(
            r -> {
              long id = Input.number(r.get("id"));
              return switch (type) {
                case "students" -> scope.canStudent(id);
                case "classes" -> scope.canClass(id);
                case "teachers" -> {
                  if (a.role().equals("TEACHER")) yield Objects.equals(a.teacherId(), id);
                  yield db
                      .list("SELECT id FROM classes WHERE teacher_id=#{p.id}", Map.of("id", id))
                      .stream()
                      .anyMatch(c -> scope.canClass(Input.number(c.get("id"))));
                }
                default -> true;
              };
            })
        .toList();
  }

  public Map<String, Object> detail(String type, long id) {
    return list(type).stream()
        .filter(r -> Input.number(r.get("id")) == id)
        .findFirst()
        .orElseThrow(() -> new BusinessException(404, "记录不存在或无权查看"));
  }

  @Transactional
  public Map<String, Object> create(String type, Map<String, Object> p) {
    type(type);
    Actor.requireRoles("ADMIN");
    Actor.requireWritable();
    var row = fields(type, p);
    long id = db.insert(type, row);
    audit.log("CREATE", type, id, "新增档案");
    if ((type.equals("students") || type.equals("teachers")) && p.get("username") != null) {
      Map<String, Object> u = new HashMap<>();
      u.put("username", p.get("username"));
      u.put("password", p.get("password"));
      u.put("displayName", p.get("name"));
      u.put("role", type.equals("students") ? "STUDENT" : "TEACHER");
      u.put(type.equals("students") ? "studentId" : "teacherId", id);
      createUser(u);
    }
    return detail(type, id);
  }

  @Transactional
  public Map<String, Object> update(String type, long id, Map<String, Object> p) {
    type(type);
    Actor.requireRoles("ADMIN");
    Actor.requireWritable();
    if (type.equals("classes") || type.equals("rooms"))
      db.one("SELECT id FROM schedule_guard WHERE id=1 FOR UPDATE", Map.of());
    var existing =
        db.one("SELECT * FROM " + type + " WHERE id=#{p.id} FOR UPDATE", Map.of("id", id));
    if (existing == null) throw new BusinessException(404, "记录不存在");
    Map<String, Object> merged = new HashMap<>(existing);
    merged.putAll(p);
    var row = fields(type, merged);
    if (type.equals("classes")) {
      if (!Objects.equals(existing.get("status"), row.get("status")))
        throw new BusinessException(400, "请通过结班操作变更班级状态");
      if (EnrollmentPeriods.peakOccupancy(db, id, LocalDate.now(ZoneId.of("Asia/Shanghai")))
          > ((Number) row.get("capacity")).intValue())
        throw new BusinessException(409, "班级容量不能小于当前或已预约报名的最高人数");
      if (Input.number(existing.get("courseId")) != Input.number(row.get("course_id"))
          && (db.count("SELECT COUNT(*) FROM enrollments WHERE class_id=#{p.id}", Map.of("id", id))
                  > 0
              || db.count("SELECT COUNT(*) FROM lessons WHERE class_id=#{p.id}", Map.of("id", id))
                  > 0)) throw new BusinessException(409, "已有报名或课次的班级不能更换课程，请新建班级");
    }
    if (type.equals("classes") || type.equals("rooms")) {
      String column = type.equals("classes") ? "class_id" : "room_id";
      if (db.count(
              "SELECT COUNT(*) FROM lessons l WHERE l."
                  + column
                  + "=#{p.id} AND l.status<>'CANCELLED' AND l.end_time>=NOW() AND (SELECT COUNT(*)"
                  + " FROM lesson_participants lp WHERE lp.lesson_id=l.id AND"
                  + " lp.status='ACTIVE')>#{p.capacity}",
              Map.of("id", id, "capacity", row.get("capacity")))
          > 0) throw new BusinessException(409, "容量不能小于待上课次的实际人数（含补课学员）");
    }
    String assignments =
        String.join(",", row.keySet().stream().map(k -> "`" + k + "`=#{p." + k + "}").toList());
    row.put("id", id);
    db.update("UPDATE " + type + " SET " + assignments + " WHERE id=#{p.id}", row);
    audit.log("UPDATE", type, id, "更新档案");
    return detail(type, id);
  }

  @Transactional
  public void delete(String type, long id) {
    type(type);
    Actor.requireRoles("ADMIN");
    Actor.requireWritable();
    if (db.update("DELETE FROM " + type + " WHERE id=#{p.id}", Map.of("id", id)) == 0)
      throw new BusinessException(404, "记录不存在");
    audit.log("DELETE", type, id, "删除未被引用的档案");
  }

  private Map<String, Object> fields(String type, Map<String, Object> p) {
    Map<String, Object> r = new LinkedHashMap<>();
    r.put("name", Input.text(p, "name", type.equals("students") || type.equals("teachers") ? 80 : 100, true));
    switch (type) {
      case "students" -> {
        r.put("gender", Input.text(p, "gender", 20, false));
        String birthday = Input.text(p, "birthday", 20, false);
        if (birthday != null) {
          try {
            LocalDate d = LocalDate.parse(birthday.substring(0, 10));
            if (d.isAfter(LocalDate.now()) || d.isBefore(LocalDate.of(1900, 1, 1)))
              throw new Exception();
            birthday = d.toString();
          } catch (Exception e) {
            throw new BusinessException(400, "出生日期不正确");
          }
        }
        r.put("birthday", birthday);
        r.put("phone", Input.text(p, "phone", 30, false));
        r.put("guardian_name", Input.text(p, "guardianName", 80, false));
        r.put("guardian_phone", Input.text(p, "guardianPhone", 30, false));
        r.put("notes", Input.text(p, "notes", 2000, false));
      }
      case "teachers" -> {
        r.put("phone", Input.text(p, "phone", 30, false));
        r.put("specialty", Input.text(p, "specialty", 255, false));
      }
      case "courses" -> {
        r.put("description", Input.text(p, "description", 3000, false));
        r.put("total_lessons", Input.positive(p, "totalLessons", 500, 12));
      }
      case "rooms" -> {
        r.put("capacity", Input.positive(p, "capacity", 500, 16));
        r.put("location", Input.text(p, "location", 255, false));
      }
      case "classes" -> {
        Long courseId = Input.id(p, "courseId", true), teacherId = Input.id(p, "teacherId", true);
        exists("courses", courseId);
        exists("teachers", teacherId);
        r.put("course_id", courseId);
        r.put("teacher_id", teacherId);
        r.put("capacity", Input.positive(p, "capacity", 500, 12));
        String status = Input.text(p, "status", 20, false);
        if (status == null) status = "ACTIVE";
        if (!Set.of("ACTIVE", "CLOSED").contains(status))
          throw new BusinessException(400, "班级状态不正确");
        r.put("status", status);
      }
    }
    return r;
  }

  private void exists(String table, long id) {
    if (db.count("SELECT COUNT(*) FROM " + table + " WHERE id=#{p.id}", Map.of("id", id)) == 0)
      throw new BusinessException(400, "关联记录不存在");
  }

  public List<Map<String, Object>> users() {
    Actor.requireRoles("ADMIN");
    return db.list(
        "SELECT"
            + " u.id,u.username,u.display_name,u.role,u.student_id,u.teacher_id,u.read_only,u.enabled,u.created_at,s.name"
            + " student_name,t.name teacher_name FROM users u LEFT JOIN students s ON"
            + " s.id=u.student_id LEFT JOIN teachers t ON t.id=u.teacher_id ORDER BY u.id",
        Map.of());
  }

  @Transactional
  public Map<String, Object> createUser(Map<String, Object> p) {
    Actor.requireRoles("ADMIN");
    Actor.requireWritable();
    lockAdministratorChanges();
    var r = userFields(p);
    r.put("password_hash", auth.hash(Input.text(p, "password", 128, true)));
    long id = db.insert("users", r);
    audit.log("CREATE_USER", "USER", id, "新增用户并绑定档案");
    return Map.of("id", id);
  }

  @Transactional
  public Map<String, Object> updateUser(long id, Map<String, Object> p) {
    Actor.requireRoles("ADMIN");
    Actor.requireWritable();
    lockAdministratorChanges();
    var old = db.one("SELECT * FROM users WHERE id=#{p.id} FOR UPDATE", Map.of("id", id));
    if (old == null) throw new BusinessException(404, "用户不存在");
    Map<String, Object> merged = new HashMap<>(old);
    merged.putAll(p);
    var r = userFields(merged);
    if (id == Actor.current().id()
        && (!"ADMIN".equals(r.get("role")) || !Input.bool(r.get("enabled"))))
      throw new BusinessException(400, "不能取消自己的管理员权限或停用自己");
    if ("ADMIN".equals(old.get("role")) && Input.bool(old.get("enabled"))
        && (!"ADMIN".equals(r.get("role")) || !Input.bool(r.get("enabled")))
        && db.count("SELECT COUNT(*) FROM users WHERE role='ADMIN' AND enabled=TRUE", Map.of()) <= 1)
      throw new BusinessException(409, "系统必须保留至少一个启用的管理员账户");
    String password = Input.text(p, "password", 128, false);
    if (password != null) r.put("password_hash", auth.hash(password));
    String assignments =
        String.join(",", r.keySet().stream().map(k -> "`" + k + "`=#{p." + k + "}").toList());
    r.put("id", id);
    db.update(
        "UPDATE users SET " + assignments + ",auth_version=auth_version+1 WHERE id=#{p.id}", r);
    audit.log("UPDATE_USER", "USER", id, "修改账户或授权，撤销旧会话");
    return Map.of("id", id);
  }

  private void lockAdministratorChanges() {
    // 管理员同时修改对方账户时先按固定顺序锁定，避免失去管理入口或互锁。
    db.list("SELECT id FROM users WHERE role='ADMIN' ORDER BY id FOR UPDATE", Map.of());
    var current = db.one("SELECT role,enabled FROM users WHERE id=#{p.id}",
        Map.of("id", Actor.current().id()));
    if (current == null || !"ADMIN".equals(current.get("role")) || !Input.bool(current.get("enabled")))
      throw new BusinessException(403, "管理员权限已变更，请重新登录");
  }

  private Map<String, Object> userFields(Map<String, Object> p) {
    String username = Input.text(p, "username", 64, true);
    if (!username.matches("[a-zA-Z0-9_.-]{3,64}"))
      throw new BusinessException(400, "用户名为3至64位字母、数字、点、下划线或横线");
    String role = Input.text(p, "role", 20, true);
    if (!Set.of("ADMIN", "TEACHER", "STUDENT").contains(role))
      throw new BusinessException(400, "角色不正确");
    Long sid = role.equals("STUDENT") ? Input.id(p, "studentId", true) : null,
        tid = role.equals("TEACHER") ? Input.id(p, "teacherId", true) : null;
    if (sid != null) exists("students", sid);
    if (tid != null) exists("teachers", tid);
    boolean ro = Input.bool(p.get("readOnly"));
    if (ro && !role.equals("STUDENT")) throw new BusinessException(400, "只读监护人账户必须绑定学员");
    Map<String, Object> r = new LinkedHashMap<>();
    r.put("username", username);
    r.put("display_name", Input.text(p, "displayName", 80, true));
    r.put("role", role);
    r.put("student_id", sid);
    r.put("teacher_id", tid);
    r.put("read_only", ro);
    r.put("enabled", p.get("enabled") == null || Input.bool(p.get("enabled")));
    return r;
  }
}
