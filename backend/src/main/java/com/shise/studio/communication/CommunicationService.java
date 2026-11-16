package com.shise.studio.communication;

import com.shise.studio.common.*;
import com.shise.studio.files.FileService;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CommunicationService {
  private final Db db;
  private final Scope scope;
  private final Audit audit;
  private final FileService files;

  public CommunicationService(Db db, Scope scope, Audit audit, FileService files) {
    this.db = db;
    this.scope = scope;
    this.audit = audit;
    this.files = files;
  }

  public List<Map<String, Object>> resources() {
    return db
        .list(
            "SELECT r.*,c.name class_name,u.display_name"
                + " creator_name,f.original_name,f.content_type FROM resources r JOIN classes c ON"
                + " c.id=r.class_id JOIN users u ON u.id=r.created_by JOIN files f ON"
                + " f.id=r.file_id ORDER BY r.id DESC",
            Map.of())
        .stream()
        .filter(r -> scope.canClass(Input.number(r.get("classId"))))
        .toList();
  }

  @Transactional
  public Map<String, Object> resource(Map<String, Object> p) {
    Actor.requireWritable();
    long cid = Input.id(p, "classId", true);
    scope.requireTeacherClass(cid);
    long fid = Input.id(p, "fileId", true);
    files.owned(fid);
    Map<String, Object> r = new LinkedHashMap<>();
    r.put("title", Input.text(p, "title", 120, true));
    r.put("class_id", cid);
    r.put("file_id", fid);
    r.put("description", Input.text(p, "description", 3000, false));
    r.put("created_by", Actor.current().id());
    long id = db.insert("resources", r);
    audit.log("PUBLISH_RESOURCE", "RESOURCE", id, "发布教学资源");
    return Map.of("id", id);
  }

  @Transactional
  public void deleteResource(long id) {
    var r = db.one("SELECT * FROM resources WHERE id=#{p.id}", Map.of("id", id));
    if (r == null) throw new BusinessException(404, "资源不存在");
    Actor.requireWritable();
    scope.requireTeacherClass(Input.number(r.get("classId")));
    db.update("DELETE FROM resources WHERE id=#{p.id}", Map.of("id", id));
    audit.log("DELETE_RESOURCE", "RESOURCE", id, "删除资源关联，保留文件");
  }

  public List<Map<String, Object>> notices() {
    return db
        .list(
            "SELECT n.*,c.name class_name,u.display_name creator_name,CASE WHEN nr.id IS NULL THEN"
                + " FALSE ELSE TRUE END `read` FROM notices n LEFT JOIN classes c ON"
                + " c.id=n.class_id JOIN users u ON u.id=n.created_by LEFT JOIN notice_reads nr ON"
                + " nr.notice_id=n.id AND nr.user_id=#{p.uid} ORDER BY n.id DESC",
            Map.of("uid", Actor.current().id()))
        .stream()
        .filter(n -> n.get("classId") == null || scope.canClass(Input.number(n.get("classId"))))
        .toList();
  }

  @Transactional
  public Map<String, Object> notice(Map<String, Object> p) {
    Actor.requireWritable();
    Actor.requireRoles("ADMIN", "TEACHER");
    Long cid = Input.id(p, "classId", false);
    if (cid == null) Actor.requireRoles("ADMIN");
    else scope.requireTeacherClass(cid);
    Map<String, Object> r = new LinkedHashMap<>();
    r.put("title", Input.text(p, "title", 120, true));
    r.put("content", Input.text(p, "content", 10000, true));
    r.put("class_id", cid);
    r.put("created_by", Actor.current().id());
    long id = db.insert("notices", r);
    audit.log("PUBLISH_NOTICE", "NOTICE", id, "发布通知");
    return Map.of("id", id);
  }

  public void read(long id) {
    var n = db.one("SELECT class_id FROM notices WHERE id=#{p.id}", Map.of("id", id));
    if (n == null) throw new BusinessException(404, "通知不存在");
    if (n.get("classId") != null) scope.requireClass(Input.number(n.get("classId")));
    db.update(
        "INSERT INTO notice_reads(notice_id,user_id) VALUES(#{p.id},#{p.uid}) ON DUPLICATE KEY"
            + " UPDATE read_at=read_at",
        Map.of("id", id, "uid", Actor.current().id()));
  }

  public List<Map<String, Object>> feedback() {
    boolean admin = Actor.current().role().equals("ADMIN");
    return db.list(
        "SELECT f.*,u.display_name user_name FROM feedback f JOIN users u ON u.id=f.user_id"
            + (admin ? "" : " WHERE f.user_id=#{p.uid}")
            + " ORDER BY f.id DESC",
        Map.of("uid", Actor.current().id()));
  }

  @Transactional
  public Map<String, Object> feedback(Map<String, Object> p) {
    Actor.requireWritable();
    long id =
        db.insert(
            "feedback",
            Map.of(
                "user_id", Actor.current().id(), "content", Input.text(p, "content", 5000, true)));
    audit.log("SUBMIT_FEEDBACK", "FEEDBACK", id, "提交反馈");
    return Map.of("id", id);
  }

  @Transactional
  public void reply(long id, Map<String, Object> p) {
    Actor.requireRoles("ADMIN");
    String reply = Input.text(p, "reply", 5000, true);
    if (db.update(
            "UPDATE feedback SET reply=#{p.reply},status='REPLIED',replied_at=NOW() WHERE"
                + " id=#{p.id}",
            Map.of("id", id, "reply", reply))
        == 0) throw new BusinessException(404, "反馈不存在");
    audit.log("REPLY_FEEDBACK", "FEEDBACK", id, "回复反馈");
  }

  public List<Map<String, Object>> logs() {
    Actor.requireRoles("ADMIN");
    return db.list(
        "SELECT a.*,u.display_name user_name FROM audit_logs a JOIN users u ON u.id=a.user_id ORDER"
            + " BY a.id DESC LIMIT 1000",
        Map.of());
  }
}
