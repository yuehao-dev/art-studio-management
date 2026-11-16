package com.shise.studio.business;

import static com.shise.studio.business.BusinessSupport.*;

import com.shise.studio.common.Db;
import java.time.*;
import java.util.*;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Local demo fixtures only. Does not run in production or reset an existing teaching database. */
@Component
@Profile("dev")
@Order(20)
public class BusinessSeed implements ApplicationRunner {
  private final Db db;

  public BusinessSeed(Db db) {
    this.db = db;
  }

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    if (db.count("SELECT COUNT(*) FROM lessons", Map.of()) > 0
        || db.count("SELECT COUNT(*) FROM students WHERE id=1", Map.of()) == 0) return;
    LocalDate today = LocalDate.now();
    db.insert(
        "enrollments",
        p(
            "student_id",
            1,
            "class_id",
            1,
            "effective_date",
            today.minusDays(60),
            "status",
            "ACTIVE"));
    db.insert(
        "enrollments",
        p(
            "student_id",
            2,
            "class_id",
            1,
            "effective_date",
            today.minusDays(60),
            "status",
            "ACTIVE"));
    db.insert(
        "enrollments",
        p(
            "student_id",
            3,
            "class_id",
            2,
            "effective_date",
            today.minusDays(60),
            "status",
            "ACTIVE"));
    long workshop =
        db.insert(
            "classes",
            p(
                "name",
                "素描补课工作坊",
                "course_id",
                1,
                "teacher_id",
                1,
                "capacity",
                8,
                "status",
                "ACTIVE"));
    long first = lesson(1, 1, 1, "几何形体与明暗关系", today.minusDays(14).atTime(14, 0), "COMPLETED");
    long first1 = participant(first, 1, "REGULAR", null),
        first2 = participant(first, 2, "REGULAR", null);
    attendance(first1, "PRESENT");
    attendance(first2, "LEAVE");
    db.insert(
        "leaves",
        p(
            "participant_id",
            first2,
            "student_id",
            2,
            "lesson_id",
            first,
            "reason",
            "演示数据：因学校活动请假",
            "status",
            "APPROVED",
            "review_comment",
            "同意，后续安排补课",
            "reviewed_by",
            2,
            "reviewed_at",
            today.minusDays(15).atTime(10, 0)));
    long second = lesson(1, 1, 1, "静物组合与构图", today.minusDays(7).atTime(14, 0), "COMPLETED");
    long second1 = participant(second, 1, "REGULAR", null),
        second2 = participant(second, 2, "REGULAR", null);
    attendance(second1, "ABSENT");
    attendance(second2, "PRESENT");
    db.insert(
        "makeup_suggestions",
        p(
            "original_participant_id",
            second1,
            "comment",
            "建议参加素描补课工作坊，重点练习构图比例。",
            "teacher_id",
            1,
            "created_by",
            2));
    long makeuplesson =
        lesson(workshop, 1, 1, "明暗关系补课辅导", today.minusDays(3).atTime(16, 0), "COMPLETED");
    long makeupPart = participant(makeuplesson, 2, "MAKEUP", first2);
    attendance(makeupPart, "PRESENT");
    db.insert(
        "makeups",
        p(
            "original_participant_id",
            first2,
            "target_lesson_id",
            makeuplesson,
            "target_participant_id",
            makeupPart,
            "student_id",
            2,
            "status",
            "COMPLETED",
            "reason",
            "演示数据：请假补课",
            "created_by",
            1,
            "completed_at",
            today.minusDays(3).atTime(18, 0)));
    long watercolor = lesson(2, 2, 2, "水彩的色彩层次", today.minusDays(2).atTime(10, 0), "COMPLETED");
    attendance(participant(watercolor, 3, "REGULAR", null), "PRESENT");
    long upcoming = lesson(1, 1, 1, "石膏头像观察与起形", today.plusDays(1).atTime(14, 0), "SCHEDULED");
    participant(upcoming, 1, "REGULAR", null);
    participant(upcoming, 2, "REGULAR", null);
    long next = lesson(1, 1, 1, "线条表现与结构练习", today.plusDays(8).atTime(14, 0), "SCHEDULED");
    participant(next, 1, "REGULAR", null);
    participant(next, 2, "REGULAR", null);
    long wc = lesson(2, 2, 2, "用水彩记录秋日植物", today.plusDays(2).atTime(10, 0), "SCHEDULED");
    participant(wc, 3, "REGULAR", null);
    lesson(workshop, 1, 1, "构图与比例补课辅导", today.plusDays(3).atTime(16, 0), "SCHEDULED");
    db.insert(
        "tasks",
        p(
            "class_id",
            1,
            "lesson_id",
            second,
            "title",
            "一组静物的光与影",
            "description",
            "选择三个不同形体的物体，完成一幅铅笔素描。保留起稿、调整和完成稿，重点观察明暗交界线。",
            "due_date",
            today.plusDays(7),
            "created_by",
            2));
    db.insert(
        "tasks",
        p(
            "class_id",
            1,
            "lesson_id",
            upcoming,
            "title",
            "石膏头像结构速写",
            "description",
            "用简练线条概括头像结构，提交后根据教师点评上传修改版本。",
            "due_date",
            today.plusDays(10),
            "created_by",
            2));
    db.insert(
        "tasks",
        p(
            "class_id",
            2,
            "lesson_id",
            wc,
            "title",
            "秋日植物色彩日记",
            "description",
            "观察一片叶子的颜色变化，用水彩记录层次，附上你的观察笔记。",
            "due_date",
            today.plusDays(9),
            "created_by",
            5));
  }

  private long lesson(
      long cls, long teacher, long room, String title, LocalDateTime start, String status) {
    return db.insert(
        "lessons",
        p(
            "class_id",
            cls,
            "teacher_id",
            teacher,
            "room_id",
            room,
            "title",
            title,
            "start_time",
            start,
            "end_time",
            start.plusHours(2),
            "status",
            status,
            "created_by",
            1));
  }

  private long participant(long lesson, long student, String kind, Long original) {
    return db.insert(
        "lesson_participants",
        p(
            "lesson_id",
            lesson,
            "student_id",
            student,
            "kind",
            kind,
            "status",
            "ACTIVE",
            "original_participant_id",
            original));
  }

  private void attendance(long participant, String status) {
    db.insert(
        "attendance",
        p("participant_id", participant, "status", status, "remark", "演示教学记录", "recorded_by", 2));
  }
}
