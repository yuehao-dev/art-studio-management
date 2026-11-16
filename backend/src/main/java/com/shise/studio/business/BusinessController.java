package com.shise.studio.business;

import com.shise.studio.common.ApiResponse;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class BusinessController {
  private final EnrollmentService enrollment;
  private final ScheduleService schedule;
  private final LeaveMakeupService leaveMakeup;
  private final LearningService learning;
  private final StatisticsService stats;

  public BusinessController(
      EnrollmentService enrollment,
      ScheduleService schedule,
      LeaveMakeupService leaveMakeup,
      LearningService learning,
      StatisticsService stats) {
    this.enrollment = enrollment;
    this.schedule = schedule;
    this.leaveMakeup = leaveMakeup;
    this.learning = learning;
    this.stats = stats;
  }

  @GetMapping("/enrollments")
  public ApiResponse<?> enrollments() {
    return ApiResponse.ok(enrollment.list());
  }

  @PostMapping("/enrollments")
  public ApiResponse<?> enroll(@RequestBody Map<String, Object> body) {
    return ApiResponse.ok(enrollment.enroll(body));
  }

  @PostMapping("/enrollments/{id}/transfer")
  public ApiResponse<?> transfer(@PathVariable long id, @RequestBody Map<String, Object> body) {
    return ApiResponse.ok(enrollment.transfer(id, body));
  }

  @PostMapping("/classes/{id}/close")
  public ApiResponse<?> close(@PathVariable long id) {
    enrollment.close(id);
    return ApiResponse.ok();
  }

  @GetMapping("/lessons")
  public ApiResponse<?> lessons(
      @RequestParam(required = false) Long classId,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to) {
    return ApiResponse.ok(schedule.lessons(classId, from, to));
  }

  @PostMapping("/lessons")
  public ApiResponse<?> lesson(@RequestBody Map<String, Object> body) {
    return ApiResponse.ok(schedule.save(null, body));
  }

  @PutMapping("/lessons/{id}")
  public ApiResponse<?> lessonUpdate(@PathVariable long id, @RequestBody Map<String, Object> body) {
    return ApiResponse.ok(schedule.save(id, body));
  }

  @PostMapping("/lessons/{id}/cancel")
  public ApiResponse<?> lessonCancel(@PathVariable long id, @RequestBody Map<String, Object> body) {
    schedule.cancel(id, body);
    return ApiResponse.ok();
  }

  @GetMapping("/lessons/{id}/participants")
  public ApiResponse<?> participants(@PathVariable long id) {
    return ApiResponse.ok(schedule.participants(id));
  }

  @PostMapping("/lessons/{id}/attendance")
  public ApiResponse<?> attendance(@PathVariable long id, @RequestBody Map<String, Object> body) {
    schedule.attendance(id, body);
    return ApiResponse.ok();
  }

  @GetMapping("/leaves")
  public ApiResponse<?> leaves() {
    return ApiResponse.ok(leaveMakeup.leaves());
  }

  @PostMapping("/leaves")
  public ApiResponse<?> leave(@RequestBody Map<String, Object> body) {
    return ApiResponse.ok(leaveMakeup.apply(body));
  }

  @PostMapping("/leaves/{id}/review")
  public ApiResponse<?> leaveReview(@PathVariable long id, @RequestBody Map<String, Object> body) {
    return ApiResponse.ok(leaveMakeup.review(id, body));
  }

  @GetMapping("/makeups")
  public ApiResponse<?> makeups() {
    return ApiResponse.ok(leaveMakeup.makeups());
  }

  @PostMapping("/makeups")
  public ApiResponse<?> makeup(@RequestBody Map<String, Object> body) {
    return ApiResponse.ok(leaveMakeup.arrange(body));
  }

  @PostMapping("/makeups/{id}/cancel")
  public ApiResponse<?> makeupCancel(@PathVariable long id, @RequestBody Map<String, Object> body) {
    leaveMakeup.cancel(id, body);
    return ApiResponse.ok();
  }

  @GetMapping("/makeup-suggestions")
  public ApiResponse<?> suggestions() {
    return ApiResponse.ok(leaveMakeup.suggestions());
  }

  @PostMapping("/makeup-suggestions")
  public ApiResponse<?> suggestion(@RequestBody Map<String, Object> body) {
    return ApiResponse.ok(leaveMakeup.suggest(body));
  }

  @GetMapping("/tasks")
  public ApiResponse<?> tasks() {
    return ApiResponse.ok(learning.tasks());
  }

  @PostMapping("/tasks")
  public ApiResponse<?> task(@RequestBody Map<String, Object> body) {
    return ApiResponse.ok(learning.task(body));
  }

  @PutMapping("/tasks/{id}")
  public ApiResponse<?> taskUpdate(@PathVariable long id, @RequestBody Map<String, Object> body) {
    return ApiResponse.ok(learning.updateTask(id, body));
  }

  @GetMapping("/works")
  public ApiResponse<?> works() {
    return ApiResponse.ok(learning.works());
  }

  @PostMapping("/works")
  public ApiResponse<?> work(@RequestBody Map<String, Object> body) {
    return ApiResponse.ok(learning.submit(body));
  }

  @GetMapping("/works/{id}/versions")
  public ApiResponse<?> versions(@PathVariable long id) {
    return ApiResponse.ok(learning.versions(id));
  }

  @PostMapping("/works/{id}/versions")
  public ApiResponse<?> version(@PathVariable long id, @RequestBody Map<String, Object> body) {
    return ApiResponse.ok(learning.version(id, body));
  }

  @PostMapping("/works/{id}/reviews")
  public ApiResponse<?> review(@PathVariable long id, @RequestBody Map<String, Object> body) {
    return ApiResponse.ok(learning.review(id, body));
  }

  @GetMapping("/progress")
  public ApiResponse<?> progress(
      @RequestParam(required = false) Long classId,
      @RequestParam(required = false) Long studentId,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to) {
    return ApiResponse.ok(stats.progress(classId, studentId, from, to));
  }

  @GetMapping("/dashboard")
  public ApiResponse<?> dashboard() {
    return ApiResponse.ok(stats.dashboard());
  }
}
