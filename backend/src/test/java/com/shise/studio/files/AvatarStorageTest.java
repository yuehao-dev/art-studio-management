package com.shise.studio.files;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.shise.studio.auth.AuthService;
import com.shise.studio.auth.JwtService;
import com.shise.studio.common.*;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

class AvatarStorageTest {
  @TempDir Path directory;
  Db db;
  FileService files;

  @BeforeEach
  void setup() {
    db = mock(Db.class);
    when(db.insert(eq("files"), anyMap())).thenReturn(42L);
    files = new FileService(db, mock(Scope.class), mock(Audit.class), mock(JwtService.class),
        mock(AuthService.class), "local", directory.toString(), "", "", "", "");
    actor("STUDENT", false);
  }

  @AfterEach
  void cleanup() {
    Actor.clear();
    files.close();
  }

  private void actor(String role, boolean readOnly) {
    Actor.set(new Actor.User(7, "sample", "示例", role, 3L, null, readOnly));
  }

  private byte[] image(int width, int height, String format) throws Exception {
    var image = new BufferedImage(width, height,
        format.equals("png") ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
    var graphics = image.createGraphics();
    graphics.setColor(Color.BLUE);
    graphics.fillRect(width / 4, height / 4, width / 2, height / 2);
    graphics.dispose();
    var bytes = new ByteArrayOutputStream();
    assertTrue(ImageIO.write(image, format, bytes));
    return bytes.toByteArray();
  }

  private MockMultipartFile png(byte[] bytes) {
    return new MockMultipartFile("file", "../任意名字.PNG", "image/png", bytes);
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private Map<String, Object> stored() {
    ArgumentCaptor<Map<String, Object>> capture = ArgumentCaptor.forClass((Class) Map.class);
    verify(db).insert(eq("files"), capture.capture());
    return capture.getValue();
  }

  @Test
  void avatarIsResizedWithWhiteBackgroundAndStoredUnderCurrentUser() throws Exception {
    var result = files.uploadAvatar(png(image(1200, 800, "png")));
    var row = stored();
    String key = (String) row.get("storage_key");
    assertTrue(key.matches("studio/avatars/\\d{4}/\\d{2}/7/[a-f0-9]{32}\\.jpg"));
    assertEquals("image/jpeg", result.get("contentType"));
    assertEquals("avatar.jpg", result.get("originalName"));
    assertEquals(7L, row.get("owner_id"));
    var normalized = ImageIO.read(directory.resolve(key).toFile());
    assertEquals(512, normalized.getWidth());
    assertEquals(341, normalized.getHeight());
    var corner = new Color(normalized.getRGB(0, 0));
    assertTrue(corner.getRed() > 245 && corner.getGreen() > 245 && corner.getBlue() > 245);
  }

  @Test
  void corruptAvatarIsRejectedBeforeStorage() throws Exception {
    byte[] valid = image(30, 30, "png");
    byte[] truncated = Arrays.copyOf(valid, 48);
    assertEquals(400, assertThrows(BusinessException.class,
        () -> files.uploadAvatar(png(truncated))).getStatus());
    verify(db, never()).insert(anyString(), anyMap());
  }

  @Test
  void forgedAvatarExtensionIsRejected() throws Exception {
    byte[] jpeg = image(20, 20, "jpg");
    assertEquals(400, assertThrows(BusinessException.class,
        () -> files.uploadAvatar(png(jpeg))).getStatus());
    verify(db, never()).insert(anyString(), anyMap());
  }

  @Test
  void oversizeAvatarIsRejectedBeforeReadingBytes() throws Exception {
    var upload = mock(MultipartFile.class);
    when(upload.getSize()).thenReturn(2L * 1024 * 1024 + 1);
    when(upload.isEmpty()).thenReturn(false);
    assertEquals(413, assertThrows(BusinessException.class,
        () -> files.uploadAvatar(upload)).getStatus());
    verify(upload, never()).getBytes();
    verify(db, never()).insert(anyString(), anyMap());
  }

  @Test
  void excessiveDimensionsAreRejectedBeforeDecoding() throws Exception {
    byte[] png = image(1, 1, "png");
    ByteBuffer.wrap(png, 16, 8).putInt(5000).putInt(5000);
    var crc = new CRC32();
    crc.update(png, 12, 17);
    ByteBuffer.wrap(png, 29, 4).putInt((int) crc.getValue());
    var error = assertThrows(BusinessException.class, () -> files.uploadAvatar(png(png)));
    assertEquals(400, error.getStatus());
    assertTrue(error.getMessage().contains("1600"));
    verify(db, never()).insert(anyString(), anyMap());
  }

  @Test
  void guardianCanUploadOwnAvatarButCannotUploadBusinessFile() throws Exception {
    actor("STUDENT", true);
    var upload = png(image(8, 8, "png"));
    assertEquals(42L, files.uploadAvatar(upload).get("id"));
    assertEquals(403, assertThrows(BusinessException.class, () -> files.upload(upload)).getStatus());
  }

  @Test
  void callerCannotUseAvatarOrTraversalPurposeThroughGeneralUpload() throws Exception {
    var upload = png(image(8, 8, "png"));
    for (String purpose : Arrays.asList("avatars", "../works", "works/../avatars", "", null)) {
      assertEquals(400, assertThrows(BusinessException.class,
          () -> files.upload(upload, purpose)).getStatus());
    }
    verify(db, never()).insert(anyString(), anyMap());
  }

  @Test
  void onlyTeachersAndAdminsMayUploadTeachingResources() throws Exception {
    var upload = png(image(8, 8, "png"));
    assertEquals(403, assertThrows(BusinessException.class,
        () -> files.upload(upload, "resources")).getStatus());
    actor("TEACHER", false);
    files.upload(upload, "resources");
    assertTrue(((String) stored().get("storage_key")).startsWith("studio/resources/"));
  }

  @Test
  void legacyUploadCallUsesAttachmentsAndPreservesOriginalBytes() throws Exception {
    byte[] original = image(20, 10, "jpg");
    var upload = new MockMultipartFile("file", "C:\\fakepath\\作业.JPEG", "image/jpeg", original);
    files.upload(upload);
    var row = stored();
    String key = (String) row.get("storage_key");
    assertTrue(key.matches("studio/attachments/\\d{4}/\\d{2}/7/[a-f0-9]{32}\\.jpg"));
    assertEquals("作业.JPEG", row.get("original_name"));
    assertArrayEquals(original, Files.readAllBytes(directory.resolve(key)));
  }

  @Test
  void repeatedWorkUploadsGenerateDifferentKeys() {
    String first = FileObjectKey.create("works", 7, "png");
    String next = FileObjectKey.create("works", 7, "png");
    assertNotEquals(first, next);
    assertTrue(first.startsWith("studio/works/"));
    assertThrows(IllegalArgumentException.class, () -> FileObjectKey.create("works/../", 7, "png"));
    assertThrows(IllegalArgumentException.class, () -> FileObjectKey.create("works", 7, "../png"));
  }
}
