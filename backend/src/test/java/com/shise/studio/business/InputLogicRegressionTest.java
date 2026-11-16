package com.shise.studio.business;

import static org.junit.jupiter.api.Assertions.*;

import com.shise.studio.common.BusinessException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InputLogicRegressionTest {
  @Test
  void fractionalIdentifiersCannotSelectAnotherRecord() {
    assertThrows(BusinessException.class, () -> BusinessSupport.id(Map.of("id", 1.9), "id"));
    assertThrows(BusinessException.class, () -> BusinessSupport.n(new BigDecimal("90.5")));
    assertEquals(7L, BusinessSupport.n(7));
  }

  @Test
  void oversizedIdentifiersCannotWrap() {
    assertThrows(BusinessException.class, () -> BusinessSupport.n(new BigDecimal("9223372036854775808")));
  }

  @Test
  void datesMustBeCompleteValidDates() {
    assertThrows(BusinessException.class, () -> BusinessSupport.date("2026-02-30", null));
    assertThrows(BusinessException.class, () -> BusinessSupport.date("2026-11-13unexpected", null));
    assertEquals(LocalDate.of(2026, 11, 13), BusinessSupport.date("2026-11-13", null));
  }
}
