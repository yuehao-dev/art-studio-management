package com.shise.studio.business;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.shise.studio.common.Db;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;

class ScheduleLogicRegressionTest {
  private final LocalDate from = LocalDate.of(2026, 11, 13);

  private Map<String, Object> enrollment(long student, int start, Integer end) {
    var row = new HashMap<String, Object>();
    row.put("studentId", student);
    row.put("effectiveDate", from.plusDays(start));
    row.put("endDate", end == null ? null : from.plusDays(end));
    return row;
  }

  private long peak(Map<String, Object>... rows) {
    Db db = mock(Db.class);
    when(db.list(anyString(), anyMap())).thenReturn(Arrays.asList(rows));
    return EnrollmentPeriods.peakOccupancy(db, 7, from);
  }

  @Test
  void endingMemberAndStartingMemberShareOnePlaceOnTheSameDate() {
    assertEquals(1, peak(enrollment(1, 10, 20), enrollment(2, 20, null)));
  }

  @Test
  void capacityReservesThePeakOfFutureOverlappingEnrollments() {
    assertEquals(3, peak(enrollment(1, -10, null), enrollment(2, 10, 30), enrollment(3, 20, null)));
  }

  @Test
  void futureTransferStillOccupiesAPlaceUntilItsEndDate() {
    assertEquals(2, peak(enrollment(1, -5, 20), enrollment(2, 0, null)));
  }

  @Test
  void zeroLengthAndExpiredIntervalsDoNotConsumeCapacity() {
    assertEquals(1, peak(enrollment(1, -10, 0), enrollment(2, 5, 5), enrollment(3, 0, null)));
  }

  @Test
  void aStudentIsCountedOnceEvenWhenHistoricalIntervalsOverlap() {
    assertEquals(2, peak(enrollment(1, -10, 20), enrollment(1, 10, null), enrollment(2, 15, null)));
  }
}
