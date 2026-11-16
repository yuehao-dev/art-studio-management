package com.shise.studio.profile;

import com.shise.studio.common.*;
import com.shise.studio.files.FileService;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class ProfileService {
  private final Db db;
  private final FileService files;
  private final Audit audit;

  public ProfileService(Db db, FileService files, Audit audit) {
    this.db = db;
    this.files = files;
    this.audit = audit;
  }

  public Map<String, Object> avatar() {
    var row = db.one("SELECT file_id FROM user_avatars WHERE user_id=#{p.id}",
        Map.of("id", Actor.current().id()));
    if (row == null) return emptyAvatar();
    long fileId = Input.number(row.get("fileId"));
    var link = files.url(fileId, false);
    return Map.of("fileId", fileId, "url", link.get("url"), "expiresIn", link.get("expiresIn"));
  }

  @Transactional
  public Map<String, Object> replaceAvatar(MultipartFile file) {
    long userId = Actor.current().id();
    var uploaded = files.uploadAvatar(file);
    long fileId = Input.number(uploaded.get("id"));
    db.update("INSERT INTO user_avatars (user_id,file_id) VALUES (#{p.userId},#{p.fileId})"
            + " ON DUPLICATE KEY UPDATE file_id=#{p.fileId},updated_at=CURRENT_TIMESTAMP",
        Map.of("userId", userId, "fileId", fileId));
    audit.log("AVATAR_CHANGE", "USER", userId, "更换本人头像，文件编号" + fileId);
    return avatar();
  }

  @Transactional
  public Map<String, Object> clearAvatar() {
    long userId = Actor.current().id();
    db.update("DELETE FROM user_avatars WHERE user_id=#{p.id}", Map.of("id", userId));
    audit.log("AVATAR_RESET", "USER", userId, "恢复默认头像");
    return emptyAvatar();
  }

  private Map<String, Object> emptyAvatar() {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("fileId", null);
    result.put("url", null);
    result.put("expiresIn", 300);
    return result;
  }
}
