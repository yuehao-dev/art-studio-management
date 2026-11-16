package com.shise.studio.files;

import com.aliyun.oss.*;
import com.aliyun.oss.model.*;
import com.shise.studio.auth.*;
import com.shise.studio.common.*;
import jakarta.annotation.PreDestroy;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ContentDisposition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class FileService {
  private static final Logger log = LoggerFactory.getLogger(FileService.class);
  private final Db db;
  private final Scope scope;
  private final Audit audit;
  private final JwtService jwt;
  private final AuthService auth;
  private final Path root;
  private final String provider, bucket;
  private final OSS oss;

  public FileService(
      Db db,
      Scope scope,
      Audit audit,
      JwtService jwt,
      AuthService auth,
      @Value("${studio.storage.provider}") String provider,
      @Value("${studio.storage.local-path}") String local,
      @Value("${studio.storage.endpoint}") String endpoint,
      @Value("${studio.storage.bucket}") String bucket,
      @Value("${studio.storage.access-key-id}") String accessKey,
      @Value("${studio.storage.access-key-secret}") String secret) {
    this.db = db;
    this.scope = scope;
    this.audit = audit;
    this.jwt = jwt;
    this.auth = auth;
    this.provider = provider.toLowerCase(Locale.ROOT);
    this.bucket = bucket;
    root = Path.of(local).toAbsolutePath().normalize();
    if (!Set.of("local", "oss").contains(this.provider))
      throw new IllegalStateException("STORAGE_PROVIDER只允许local或oss");
    if (this.provider.equals("oss")) {
      if (accessKey.isBlank() || secret.isBlank())
        throw new IllegalStateException("OSS模式需要配置OSS_ACCESS_KEY_ID和OSS_ACCESS_KEY_SECRET");
      ClientBuilderConfiguration config = new ClientBuilderConfiguration();
      config.setConnectionTimeout(5000);
      config.setSocketTimeout(10000);
      config.setMaxErrorRetry(1);
      oss = new OSSClientBuilder().build(endpoint, accessKey, secret, config);
    } else oss = null;
  }

  @PreDestroy
  public void close() {
    if (oss != null) oss.shutdown();
  }

  public Map<String, Object> upload(MultipartFile file) {
    return upload(file, "attachments");
  }

  public Map<String, Object> upload(MultipartFile file, String purpose) {
    Actor.requireWritable();
    if (purpose == null || !Set.of("works", "resources", "attachments").contains(purpose))
      throw new BusinessException(400, "文件用途不正确");
    if (purpose.equals("resources")) Actor.requireRoles("ADMIN", "TEACHER");
    if (file == null || file.isEmpty()) throw new BusinessException(400, "请选择文件");
    if (file.getSize() > 10 * 1024 * 1024) throw new BusinessException(413, "单个文件不能超过10MB");
    String original =
        Optional.ofNullable(file.getOriginalFilename()).orElse("file").replace('\\', '/');
    original = original.substring(original.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "");
    if (original.length() > 200) original = original.substring(original.length() - 200);
    String ext =
        original.contains(".")
            ? original.substring(original.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT)
            : "";
    Map<String, String> types =
        Map.of(
            "jpg",
            "image/jpeg",
            "jpeg",
            "image/jpeg",
            "png",
            "image/png",
            "webp",
            "image/webp",
            "pdf",
            "application/pdf");
    if (!types.containsKey(ext)) throw new BusinessException(400, "仅支持 JPG、PNG、WEBP 图片和 PDF 文件");
    byte[] bytes;
    try {
      bytes = file.getBytes();
    } catch (IOException e) {
      throw new BusinessException(400, "文件读取失败");
    }
    if (!signature(bytes, ext)) throw new BusinessException(400, "文件内容与扩展名不符");
    if (ext.equals("png") || ext.equals("jpg") || ext.equals("jpeg")) validateImage(bytes);
    return save(bytes, original, types.get(ext), FileObjectKey.create(purpose, Actor.current().id(), ext));
  }

  /** 头像只修改当前登录账户，不受监护人的业务只读权限限制。 */
  public Map<String, Object> uploadAvatar(MultipartFile file) {
    long owner = Actor.current().id();
    byte[] bytes = AvatarImage.normalize(file);
    return save(bytes, "avatar.jpg", "image/jpeg", FileObjectKey.create("avatars", owner, "jpg"));
  }

  private Map<String, Object> save(byte[] bytes, String original, String contentType, String key) {
    try {
      if (oss != null) {
        ObjectMetadata meta = new ObjectMetadata();
        meta.setContentType(contentType);
        meta.setContentLength(bytes.length);
        meta.setContentDisposition(
            (contentType.startsWith("image/")
                    ? ContentDisposition.inline()
                    : ContentDisposition.attachment())
                .filename(original, StandardCharsets.UTF_8)
                .build()
                .toString());
        meta.setHeader("x-oss-object-acl", "private");
        PutObjectRequest request =
            new PutObjectRequest(bucket, key, new ByteArrayInputStream(bytes));
        request.setMetadata(meta);
        oss.putObject(request);
      } else {
        Path target = resolve(key);
        Files.createDirectories(target.getParent());
        Files.write(target, bytes, StandardOpenOption.CREATE_NEW);
      }
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("owner_id", Actor.current().id());
      row.put("original_name", original);
      row.put("storage_key", key);
      row.put("content_type", contentType);
      row.put("size", bytes.length);
      row.put("provider", provider);
      long id = db.insert("files", row);
      audit.log("UPLOAD", "FILE", id, "上传" + original);
      return Map.of(
          "id", id, "originalName", original, "size", bytes.length, "contentType", contentType);
    } catch (BusinessException e) {
      throw e;
    } catch (OSSException e) {
      log.warn("OSS upload failed: code={}, requestId={}", e.getErrorCode(), e.getRequestId());
      throw new BusinessException(503, "云存储上传失败，请联系管理员检查存储权限与配置");
    } catch (ClientException e) {
      log.warn("OSS connection failed: code={}", e.getErrorCode());
      throw new BusinessException(503, "暂时无法连接云存储，请稍后重试");
    } catch (Exception e) {
      log.warn("File save failed: provider={}, type={}", provider, e.getClass().getSimpleName());
      throw new BusinessException(503, "文件保存失败，请检查存储配置与服务状态");
    }
  }

  private void validateImage(byte[] bytes) {
    try (var input =
        javax.imageio.ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
      var readers = javax.imageio.ImageIO.getImageReaders(input);
      if (!readers.hasNext()) throw new BusinessException(400, "无法解析图片");
      var reader = readers.next();
      try {
        reader.setInput(input);
        long w = reader.getWidth(0), h = reader.getHeight(0);
        if (w <= 0 || h <= 0 || w * h > 40_000_000)
          throw new BusinessException(400, "图片分辨率过大，请压缩到4000万像素以内");
      } finally {
        reader.dispose();
      }
    } catch (IOException e) {
      throw new BusinessException(400, "图片内容无效");
    }
  }

  private boolean signature(byte[] b, String ext) {
    if (b.length < 12) return false;
    return switch (ext) {
      case "jpg", "jpeg" -> (b[0] & 255) == 255 && (b[1] & 255) == 216 && (b[2] & 255) == 255;
      case "png" ->
          b[0] == (byte) 137
              && b[1] == 80
              && b[2] == 78
              && b[3] == 71
              && b[4] == 13
              && b[5] == 10
              && b[6] == 26
              && b[7] == 10;
      case "webp" ->
          new String(b, 0, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("RIFF")
              && new String(b, 8, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("WEBP");
      case "pdf" -> new String(b, 0, 5, java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF-");
      default -> false;
    };
  }

  public Map<String, Object> owned(long id) {
    var f = record(id);
    if (!Actor.current().role().equals("ADMIN")
        && Input.number(f.get("ownerId")) != Actor.current().id())
      throw new BusinessException(403, "只能关联自己上传的文件");
    return f;
  }

  private Map<String, Object> record(long id) {
    var f = db.one("SELECT * FROM files WHERE id=#{p.id}", Map.of("id", id));
    if (f == null) throw new BusinessException(404, "文件不存在");
    return f;
  }

  public Map<String, Object> authorized(long id) {
    var f = record(id);
    var a = Actor.current();
    if (a.role().equals("ADMIN") || Input.number(f.get("ownerId")) == a.id()) return f;
    boolean resource =
        db.list("SELECT class_id FROM resources WHERE file_id=#{p.id}", Map.of("id", id)).stream()
            .anyMatch(r -> scope.canClass(Input.number(r.get("classId"))));
    if (resource) return f;
    for (var w :
        db.list(
            "SELECT w.student_id,t.class_id FROM work_versions v JOIN works w ON w.id=v.work_id"
                + " JOIN tasks t ON t.id=w.task_id WHERE v.file_id=#{p.id}",
            Map.of("id", id))) {
      if (a.role().equals("STUDENT")
          && Objects.equals(a.studentId(), Input.number(w.get("studentId")))) return f;
      if (a.role().equals("TEACHER") && scope.teacherClass(Input.number(w.get("classId"))))
        return f;
    }
    throw new BusinessException(403, "没有此文件的查看权限");
  }

  public Map<String, Object> url(long id, boolean download) {
    var f = authorized(id);
    String url;
    if ("oss".equals(f.get("provider")) && !download) {
      if (oss == null) throw new BusinessException(503, "当前未启用OSS存储，请检查配置");
      url =
          oss.generatePresignedUrl(
                  bucket,
                  String.valueOf(f.get("storageKey")),
                  Date.from(Instant.now().plusSeconds(300)))
              .toString();
    } else {
      url =
          "/api/files/"
              + id
              + "/content?ticket="
              + jwt.file(Actor.current().id(), auth.version(Actor.current().id()), id);
    }
    return Map.of(
        "url",
        url,
        "expiresIn",
        300,
        "originalName",
        f.get("originalName"),
        "contentType",
        f.get("contentType"));
  }

  public Map<String, Object> authenticateTicket(long id, String ticket) {
    var c = jwt.parse(ticket, "file");
    if (((Number) c.get("fid")).longValue() != id) throw new BusinessException(403, "文件授权不匹配");
    Actor.set(auth.authenticate(c));
    return authorized(id);
  }

  public Path localFile(Map<String, Object> file) {
    if (!"local".equals(file.get("provider"))) throw new BusinessException(404, "不是本地文件");
    Path p = resolve(String.valueOf(file.get("storageKey")));
    if (!Files.isRegularFile(p)) throw new BusinessException(404, "文件内容不存在");
    return p;
  }

  public InputStream ossContent(Map<String, Object> file) {
    if (oss == null) throw new BusinessException(503, "当前未启用OSS存储，请检查配置");
    try {
      return oss.getObject(bucket, String.valueOf(file.get("storageKey"))).getObjectContent();
    } catch (OSSException e) {
      log.warn("OSS download failed: code={}, requestId={}", e.getErrorCode(), e.getRequestId());
      if ("NoSuchKey".equals(e.getErrorCode())) throw new BusinessException(404, "文件内容不存在");
      throw new BusinessException(503, "云存储读取失败，请联系管理员检查存储权限与配置");
    } catch (ClientException e) {
      log.warn("OSS download connection failed: code={}", e.getErrorCode());
      throw new BusinessException(503, "暂时无法连接云存储，请稍后重试");
    }
  }

  private Path resolve(String key) {
    Path p = root.resolve(key).normalize();
    if (!p.startsWith(root)) throw new BusinessException(400, "文件路径不正确");
    return p;
  }
}
