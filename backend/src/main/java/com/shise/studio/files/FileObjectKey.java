package com.shise.studio.files;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** 对象路径只由服务端的用途、日期、账户编号和随机编号组成，不包含原始文件名。 */
final class FileObjectKey {
  private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy/MM");
  private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

  private FileObjectKey() {}

  static String create(String purpose, long ownerId, String extension) {
    if (purpose == null || !Set.of("avatars", "works", "resources", "attachments").contains(purpose)
        || ownerId <= 0)
      throw new IllegalArgumentException("Invalid file purpose or owner");
    String ext = extension == null ? "" : extension.toLowerCase(Locale.ROOT);
    if (ext.equals("jpeg")) ext = "jpg";
    if (!Set.of("jpg", "png", "webp", "pdf").contains(ext))
      throw new IllegalArgumentException("Invalid file extension");
    return "studio/" + purpose + "/" + LocalDate.now(ZONE).format(MONTH) + "/" + ownerId
        + "/" + UUID.randomUUID().toString().replace("-", "") + "." + ext;
  }
}
