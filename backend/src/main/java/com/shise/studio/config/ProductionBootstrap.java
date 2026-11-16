package com.shise.studio.config;

import com.shise.studio.common.Db;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.*;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@Profile("prod")
@Order(10)
public class ProductionBootstrap implements ApplicationRunner {
  private final Db db;
  private final String password;

  public ProductionBootstrap(Db db, @Value("${STUDIO_BOOTSTRAP_ADMIN_PASSWORD:}") String password) {
    this.db = db;
    this.password = password;
  }

  public void run(ApplicationArguments args) {
    if (db.count("SELECT COUNT(*) FROM users", Map.of()) > 0) return;
    if (password.length() < 12
        || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72)
      throw new IllegalStateException("首次生产启动请设置12至72字节的STUDIO_BOOTSTRAP_ADMIN_PASSWORD");
    db.insert(
        "users",
        Map.of(
            "username",
            "admin",
            "display_name",
            "画室管理员",
            "role",
            "ADMIN",
            "password_hash",
            new BCryptPasswordEncoder().encode(password)));
  }
}
