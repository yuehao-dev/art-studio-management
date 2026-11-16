package com.shise.studio.files;

import com.shise.studio.common.*;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/files")
public class FileController {
  private final FileService files;

  public FileController(FileService files) {
    this.files = files;
  }

  @PostMapping
  public ApiResponse<?> upload(
      @RequestParam("file") MultipartFile file,
      @RequestParam(defaultValue = "attachments") String purpose) {
    return ApiResponse.ok(files.upload(file, purpose));
  }

  @GetMapping("/{id}/url")
  public ApiResponse<?> url(
      @PathVariable long id, @RequestParam(defaultValue = "false") boolean download) {
    return ApiResponse.ok(files.url(id, download));
  }

  @GetMapping("/{id}/content")
  public ResponseEntity<?> content(@PathVariable long id, @RequestParam String ticket) {
    try {
      var f = files.authenticateTicket(id, ticket);
      String type = String.valueOf(f.get("contentType"));
      var disposition =
          (type.startsWith("image/")
                  ? ContentDisposition.inline()
                  : ContentDisposition.attachment())
              .filename(String.valueOf(f.get("originalName")), StandardCharsets.UTF_8)
              .build();
      Resource content = "oss".equals(f.get("provider"))
          ? new InputStreamResource(files.ossContent(f))
          : new FileSystemResource(files.localFile(f));
      return ResponseEntity.ok()
          .contentType(MediaType.parseMediaType(type))
          .contentLength(Input.number(f.get("size")))
          .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
          .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
          .header("X-Content-Type-Options", "nosniff")
          .body(content);
    } finally {
      Actor.clear();
    }
  }
}
