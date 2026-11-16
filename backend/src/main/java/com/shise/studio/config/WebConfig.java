package com.shise.studio.config;

import com.shise.studio.auth.AuthService;
import com.shise.studio.common.*;
import jakarta.servlet.http.*;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.*;
import org.springframework.web.servlet.config.annotation.*;

@Configuration
public class WebConfig implements WebMvcConfigurer {
  private final AuthService auth;

  public WebConfig(AuthService auth) {
    this.auth = auth;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry
        .addInterceptor(
            new HandlerInterceptor() {
              @Override
              public boolean preHandle(
                  HttpServletRequest req, HttpServletResponse res, Object handler) {
                Actor.clear();
                res.setHeader("X-Content-Type-Options", "nosniff");
                res.setHeader("Referrer-Policy", "same-origin");
                String path = req.getRequestURI();
                String method = req.getMethod();
                if (method.equals("OPTIONS")
                    || path.equals("/api/auth/login")
                    || (path.equals("/api/health") && method.equals("GET"))
                    || (path.matches("/api/files/\\d+/content") && method.equals("GET")))
                  return true;
                String header = req.getHeader("Authorization");
                if (header == null || !header.startsWith("Bearer "))
                  throw new BusinessException(401, "请先登录");
                try {
                  Actor.set(auth.fromAccess(header.substring(7)));
                  boolean passive =
                      method.equals("GET")
                          || method.equals("HEAD")
                          || path.equals("/api/auth/logout")
                          || path.equals("/api/auth/password")
                          || (path.equals("/api/profile/avatar")
                              && (method.equals("POST") || method.equals("DELETE")))
                          || path.matches("/api/notices/\\d+/read");
                  if (!passive) Actor.requireWritable();
                  return true;
                } catch (RuntimeException e) {
                  Actor.clear();
                  throw e;
                }
              }

              @Override
              public void afterCompletion(
                  HttpServletRequest request,
                  HttpServletResponse response,
                  Object handler,
                  Exception ex) {
                Actor.clear();
              }
            })
        .addPathPatterns("/api/**");
  }

  @Override
  public void addCorsMappings(CorsRegistry registry) {
    registry
        .addMapping("/api/**")
        .allowedOrigins("http://localhost:5173", "http://127.0.0.1:5173")
        .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
        .allowedHeaders("Authorization", "Content-Type")
        .maxAge(3600);
  }
}
