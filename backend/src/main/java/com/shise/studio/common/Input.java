package com.shise.studio.common;

import java.util.*;

public final class Input {
  private Input() {}

  public static String text(Map<String, Object> p, String key, int max, boolean required) {
    Object v = p.get(key);
    String s = v == null ? null : String.valueOf(v).trim();
    if (s != null && s.isEmpty()) s = null;
    if (required && s == null) throw new BusinessException(400, key + "不能为空");
    if (s != null && s.length() > max) throw new BusinessException(400, key + "长度不能超过" + max);
    return s;
  }

  public static Long id(Map<String, Object> p, String key, boolean required) {
    Object v = p.get(key);
    if (v == null || "".equals(v)) {
      if (required) throw new BusinessException(400, key + "不能为空");
      return null;
    }
    try {
      long n = Long.parseLong(v.toString());
      if (n <= 0) throw new Exception();
      return n;
    } catch (Exception e) {
      throw new BusinessException(400, key + "必须为正整数");
    }
  }

  public static int positive(Map<String, Object> p, String key, int max, int fallback) {
    Object v = p.get(key);
    if (v == null) return fallback;
    try {
      int n = Integer.parseInt(v.toString());
      if (n < 1 || n > max) throw new Exception();
      return n;
    } catch (Exception e) {
      throw new BusinessException(400, key + "必须在1到" + max + "之间");
    }
  }

  public static boolean bool(Object v) {
    return Boolean.TRUE.equals(v)
        || "true".equalsIgnoreCase(String.valueOf(v))
        || "1".equals(String.valueOf(v));
  }

  public static long number(Object v) {
    return ((Number) v).longValue();
  }
}
