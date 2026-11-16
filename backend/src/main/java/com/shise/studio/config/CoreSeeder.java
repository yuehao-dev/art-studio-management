package com.shise.studio.config;

import com.shise.studio.common.Db;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.*;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("dev")
@Order(10)
public class CoreSeeder implements ApplicationRunner {
  private final Db db;
  private final String password;

  public CoreSeeder(Db db, @Value("${studio.demo-password}") String password) {
    this.db = db;
    this.password = password;
  }

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    if (db.count("SELECT COUNT(*) FROM users", Map.of()) > 0) return;
    db.update(
        "INSERT INTO students(id,name,gender,birthday,phone,guardian_name,guardian_phone,notes)"
            + " VALUES"
            + " (1,'林小满','女','2012-05-18','13800000001','林先生','13900000001','示例学员，练习素描与色彩'),(2,'周予安','男','2011-09-02','13800000002','周女士','13900000002','示例学员'),(3,'陈知夏','女','2013-03-12','13800000003','陈女士','13900000003','示例学员')",
        Map.of());
    db.update(
        "INSERT INTO teachers(id,name,phone,specialty) VALUES (1,'许青禾','13700000001','素描 /"
            + " 水彩'),(2,'沈砚','13700000002','油画 / 创意绘画')",
        Map.of());
    db.update(
        "INSERT INTO courses(id,name,description,total_lessons) VALUES"
            + " (1,'素描基础','从观察、构图到明暗关系，建立扎实的绘画基础。',12),(2,'水彩与色彩','学习色彩搭配与水彩表现。',12)",
        Map.of());
    db.update(
        "INSERT INTO rooms(id,name,capacity,location) VALUES"
            + " (1,'一号教室',16,'一楼东侧'),(2,'二号教室',12,'二楼西侧')",
        Map.of());
    db.update(
        "INSERT INTO classes(id,name,course_id,teacher_id,capacity) VALUES (1,'素描启蒙 A"
            + " 班',1,1,12),(2,'周末水彩班',2,2,10)",
        Map.of());
    String hash = new BCryptPasswordEncoder().encode(password);
    addUser("admin", "管理员", "ADMIN", null, null, false, new BCryptPasswordEncoder().encode("admin"));
    addUser("teacher", "许青禾", "TEACHER", null, 1L, false, hash);
    addUser("student", "林小满", "STUDENT", 1L, null, false, hash);
    addUser("guardian", "林先生 · 监护人", "STUDENT", 1L, null, true, hash);
    addUser("teacher2", "沈砚", "TEACHER", null, 2L, false, hash);
    addUser("student2", "周予安", "STUDENT", 2L, null, false, hash);
    addUser("test", "测试学员", "STUDENT", 1L, null, false, new BCryptPasswordEncoder().encode("test"));
    db.update(
        "INSERT INTO notices(title,content,created_by) VALUES"
            + " ('系统使用说明','请在首页查看课程安排，通过左侧菜单办理教务和教学事务。',1)",
        Map.of());
  }

  private void addUser(
      String username,
      String name,
      String role,
      Long sid,
      Long tid,
      boolean readOnly,
      String hash) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("username", username);
    p.put("password_hash", hash);
    p.put("display_name", name);
    p.put("role", role);
    p.put("student_id", sid);
    p.put("teacher_id", tid);
    p.put("read_only", readOnly);
    db.insert("users", p);
  }
}
