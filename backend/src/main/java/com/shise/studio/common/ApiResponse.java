package com.shise.studio.common;

public record ApiResponse<T>(int code, String message, T data) {
  public static <T> ApiResponse<T> ok(T data) {
    return new ApiResponse<>(0, "成功", data);
  }

  public static ApiResponse<Void> ok() {
    return ok(null);
  }
}
