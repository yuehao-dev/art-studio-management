package com.shise.studio.auth;

import com.shise.studio.common.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class AuthController {
  private final AuthService auth;

  public AuthController(AuthService auth) {
    this.auth = auth;
  }

  @GetMapping("/health")
  public ApiResponse<?> health() {
    return ApiResponse.ok(Map.of("status", "UP", "name", "画室学员管理系统"));
  }

  @PostMapping("/auth/login")
  public ApiResponse<?> login(@RequestBody Map<String, Object> p, HttpServletRequest req) {
    try {
      return ApiResponse.ok(auth.login(p, req.getRemoteAddr()));
    } finally {
      Actor.clear();
    }
  }

  @GetMapping("/auth/me")
  public ApiResponse<?> me() {
    return ApiResponse.ok(Actor.current());
  }

  @PostMapping("/auth/logout")
  public ApiResponse<?> logout() {
    auth.logout();
    return ApiResponse.ok();
  }

  @PutMapping("/auth/password")
  public ApiResponse<?> password(@RequestBody Map<String, Object> p) {
    auth.password(p);
    return ApiResponse.ok();
  }
}
