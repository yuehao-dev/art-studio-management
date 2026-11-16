package com.shise.studio.files;

import com.shise.studio.common.BusinessException;
import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Locale;
import javax.imageio.ImageIO;
import org.springframework.web.multipart.MultipartFile;

/** 解码后重绘并重新编码，限制解码内存并去掉原图元数据。 */
final class AvatarImage {
  private AvatarImage() {}

  static byte[] normalize(MultipartFile file) {
    if (file == null || file.isEmpty()) throw new BusinessException(400, "请选择头像图片");
    if (file.getSize() > 2 * 1024 * 1024)
      throw new BusinessException(413, "头像不能超过2MB");
    String name = file.getOriginalFilename();
    String ext = name == null || !name.contains(".") ? ""
        : name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    if (!ext.equals("jpg") && !ext.equals("jpeg") && !ext.equals("png"))
      throw new BusinessException(400, "头像仅支持 JPG、PNG 图片");
    try {
      byte[] bytes = file.getBytes();
      if (bytes.length > 2 * 1024 * 1024) throw new BusinessException(413, "头像不能超过2MB");
      try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
        var readers = ImageIO.getImageReaders(input);
        if (!readers.hasNext()) throw new BusinessException(400, "头像图片内容无效");
        var reader = readers.next();
        try {
          String format = reader.getFormatName();
          if (!(ext.equals("png") ? format.equalsIgnoreCase("png") : format.equalsIgnoreCase("jpeg")))
            throw new BusinessException(400, "头像内容与扩展名不符");
          reader.setInput(input);
          int width = reader.getWidth(0), height = reader.getHeight(0);
          if (width <= 0 || height <= 0 || (long) width * height > 16_000_000)
            throw new BusinessException(400, "头像分辨率不能超过1600万像素");
          boolean[] damaged = {false};
          reader.addIIOReadWarningListener((source, warning) -> damaged[0] = true);
          BufferedImage decoded = reader.read(0);
          if (decoded == null || damaged[0]) throw new BusinessException(400, "头像图片不完整或已损坏");
          double ratio = Math.min(1d, 512d / Math.max(width, height));
          int targetWidth = Math.max(1, (int) Math.round(width * ratio));
          int targetHeight = Math.max(1, (int) Math.round(height * ratio));
          BufferedImage normalized = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
          var graphics = normalized.createGraphics();
          try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, targetWidth, targetHeight);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(decoded, 0, 0, targetWidth, targetHeight, null);
          } finally {
            graphics.dispose();
            decoded.flush();
          }
          try (var output = new ByteArrayOutputStream()) {
            if (!ImageIO.write(normalized, "jpg", output))
              throw new BusinessException(400, "头像转换失败");
            return output.toByteArray();
          } finally {
            normalized.flush();
          }
        } finally {
          reader.dispose();
        }
      }
    } catch (IOException | IllegalArgumentException e) {
      throw new BusinessException(400, "头像图片不完整或已损坏，请重新选择");
    }
  }
}
