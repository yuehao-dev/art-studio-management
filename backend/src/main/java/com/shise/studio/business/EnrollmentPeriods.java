package com.shise.studio.business;

import com.shise.studio.common.Db;
import com.shise.studio.common.Input;
import java.time.LocalDate;
import java.util.*;

/** 报名区间为 [生效日期, 结束日期)，预约转出的报名在结束日之前仍占名额。 */
public final class EnrollmentPeriods {
  private EnrollmentPeriods() {}

  public static long peakOccupancy(Db db, long classId, LocalDate from) {
    var rows = db.list(
        "SELECT student_id,effective_date,end_date FROM enrollments WHERE class_id=#{p.classId}"
            + " AND (end_date IS NULL OR end_date>#{p.from})",
        Map.of("classId", classId, "from", from));
    NavigableMap<LocalDate, Map<Long, Integer>> events = new TreeMap<>();
    for (var row : rows) {
      long student = Input.number(row.get("studentId"));
      LocalDate start = BusinessSupport.date(row.get("effectiveDate"), from);
      LocalDate end = BusinessSupport.date(row.get("endDate"), null);
      if (start.isBefore(from)) start = from;
      if (end != null && !end.isAfter(start)) continue;
      events.computeIfAbsent(start, ignored -> new HashMap<>()).merge(student, 1, Integer::sum);
      if (end != null)
        events.computeIfAbsent(end, ignored -> new HashMap<>()).merge(student, -1, Integer::sum);
    }
    Map<Long, Integer> active = new HashMap<>();
    long maximum = 0;
    for (var daily : events.values()) {
      // 同一天的进出合并处理，结束当天不再占用名额；同一学员只计一次。
      daily.forEach((student, delta) -> {
        int count = active.getOrDefault(student, 0) + delta;
        if (count <= 0) active.remove(student);
        else active.put(student, count);
      });
      maximum = Math.max(maximum, active.size());
    }
    return maximum;
  }
}
