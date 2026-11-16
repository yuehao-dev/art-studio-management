package com.shise.studio.auth;

import com.shise.studio.common.BusinessException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.DigestUtils;

/** 登录失败限速，Redis可选；未启用时仅为单实例内存限速。 */
@Component
public class LoginLimiter {
  private record Attempt(int failures, long until) {}

  private final ConcurrentHashMap<String, Attempt> local = new ConcurrentHashMap<>();
  private final StringRedisTemplate redis;
  private final boolean enabled;

  public LoginLimiter(
      StringRedisTemplate redis, @Value("${studio.redis-enabled:false}") boolean enabled) {
    this.redis = redis;
    this.enabled = enabled;
  }

  public String key(String username, String ip) {
    return "studio:login:"
        + DigestUtils.md5DigestAsHex(
            (username.toLowerCase(Locale.ROOT) + ":" + ip).getBytes(StandardCharsets.UTF_8));
  }

  public void check(String key) {
    if (enabled) {
      try {
        String n = redis.opsForValue().get(key);
        if (n != null && Integer.parseInt(n) >= 10)
          throw new BusinessException(429, "登录失败过多，请15分钟后重试");
        return;
      } catch (BusinessException e) {
        throw e;
      } catch (Exception ignored) {
      }
    }
    Attempt a = local.get(key);
    if (a != null && a.until() > System.currentTimeMillis() && a.failures() >= 10)
      throw new BusinessException(429, "登录失败过多，请15分钟后重试");
  }

  public void fail(String key) {
    if (enabled) {
      try {
        Long n = redis.opsForValue().increment(key);
        if (n != null && n == 1) redis.expire(key, Duration.ofMinutes(15));
        return;
      } catch (Exception ignored) {
      }
    }
    long now = System.currentTimeMillis();
    if (local.size() > 10000) local.entrySet().removeIf(e -> e.getValue().until() < now);
    local.compute(
        key,
        (k, a) ->
            a == null || a.until() < now
                ? new Attempt(1, now + 900000)
                : new Attempt(a.failures() + 1, a.until()));
  }

  public void clear(String key) {
    local.remove(key);
    if (enabled)
      try {
        redis.delete(key);
      } catch (Exception ignored) {
      }
  }
}
