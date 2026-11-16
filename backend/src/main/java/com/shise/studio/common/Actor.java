package com.shise.studio.common;

import java.util.Arrays;

public final class Actor {
  private Actor() {}

  private static final ThreadLocal<User> HOLDER = new ThreadLocal<>();

  public record User(
      long id,
      String username,
      String displayName,
      String role,
      Long studentId,
      Long teacherId,
      boolean readOnly) {}

  public static User current() {
    User u = HOLDER.get();
    if (u == null) throw new BusinessException(401, "请先登录");
    return u;
  }

  public static void set(User user) {
    HOLDER.set(user);
  }

  public static void clear() {
    HOLDER.remove();
  }

  public static void requireRoles(String... roles) {
    if (Arrays.stream(roles).noneMatch(current().role()::equals))
      throw new BusinessException(403, "没有操作权限");
  }

  public static void requireWritable() {
    if (current().readOnly()) throw new BusinessException(403, "监护人账户仅可查看授权学员信息");
  }
}
