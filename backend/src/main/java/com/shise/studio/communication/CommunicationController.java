package com.shise.studio.communication;

import com.shise.studio.common.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class CommunicationController {
  private final CommunicationService service;

  public CommunicationController(CommunicationService service) {
    this.service = service;
  }

  @GetMapping("/resources")
  public ApiResponse<?> resources() {
    return ApiResponse.ok(service.resources());
  }

  @PostMapping("/resources")
  public ApiResponse<?> resource(@RequestBody Map<String, Object> p) {
    return ApiResponse.ok(service.resource(p));
  }

  @DeleteMapping("/resources/{id}")
  public ApiResponse<?> deleteResource(@PathVariable long id) {
    service.deleteResource(id);
    return ApiResponse.ok();
  }

  @GetMapping("/notices")
  public ApiResponse<?> notices() {
    return ApiResponse.ok(service.notices());
  }

  @PostMapping("/notices")
  public ApiResponse<?> notice(@RequestBody Map<String, Object> p) {
    return ApiResponse.ok(service.notice(p));
  }

  @PostMapping("/notices/{id}/read")
  public ApiResponse<?> read(@PathVariable long id) {
    service.read(id);
    return ApiResponse.ok();
  }

  @GetMapping("/feedback")
  public ApiResponse<?> feedback() {
    return ApiResponse.ok(service.feedback());
  }

  @PostMapping("/feedback")
  public ApiResponse<?> feedback(@RequestBody Map<String, Object> p) {
    return ApiResponse.ok(service.feedback(p));
  }

  @PutMapping("/feedback/{id}/reply")
  public ApiResponse<?> reply(@PathVariable long id, @RequestBody Map<String, Object> p) {
    service.reply(id, p);
    return ApiResponse.ok();
  }

  @GetMapping("/audit-logs")
  public ApiResponse<?> logs() {
    return ApiResponse.ok(service.logs());
  }
}
