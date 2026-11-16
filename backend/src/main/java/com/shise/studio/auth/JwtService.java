package com.shise.studio.auth;

import com.shise.studio.common.BusinessException;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class JwtService {
  private final SecretKey key;

  public JwtService(@Value("${studio.jwt-secret}") String secret, Environment env) {
    if (secret.getBytes(StandardCharsets.UTF_8).length < 32)
      throw new IllegalStateException("JWT_SECRET至少32字节");
    if (Arrays.asList(env.getActiveProfiles()).contains("prod")
        && secret.startsWith("local-development"))
      throw new IllegalStateException("生产环境必须更换JWT_SECRET");
    key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
  }

  public String access(long uid, int version) {
    return token(uid, version, "access", null, 8 * 3600);
  }

  public String file(long uid, int version, long id) {
    return token(uid, version, "file", id, 300);
  }

  private String token(long uid, int version, String type, Long fileId, long seconds) {
    var b =
        Jwts.builder()
            .subject(String.valueOf(uid))
            .claim("av", version)
            .claim("type", type)
            .issuedAt(Date.from(Instant.now()))
            .expiration(Date.from(Instant.now().plusSeconds(seconds)))
            .id(UUID.randomUUID().toString());
    if (fileId != null) b.claim("fid", fileId);
    return b.signWith(key).compact();
  }

  public Claims parse(String token, String type) {
    try {
      Claims c = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
      if (!type.equals(c.get("type", String.class))) throw new IllegalArgumentException();
      return c;
    } catch (Exception e) {
      throw new BusinessException(401, "登录或授权链接已失效，请重新获取");
    }
  }
}
