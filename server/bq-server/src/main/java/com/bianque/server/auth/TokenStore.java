package com.bianque.server.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 登录态仓储：优先 Redis（后端重启不掉登录态，TTL 滑动过期），无 Redis 时回退内存。
 * key = bianque:token:<token> → AuthContext JSON。
 */
@Component
public class TokenStore {

    private static final Logger log = LoggerFactory.getLogger(TokenStore.class);
    private static final String KEY_PREFIX = "bianque:token:";

    private final Duration ttl;
    private final StringRedisTemplate redis; // 可为 null（无 Redis 环境）
    private final ObjectMapper json; // Spring 容器的（含 JavaTimeModule，能序列化 Instant）
    private final Map<String, AuthContext> memory = new ConcurrentHashMap<>();

    public TokenStore(ObjectProvider<StringRedisTemplate> redis, ObjectMapper json,
                      @Value("${bianque.auth.token-ttl-hours:8}") long ttlHours) {
        this.redis = redis.getIfAvailable();
        this.json = json;
        this.ttl = Duration.ofHours(ttlHours);
        if (this.redis != null) {
            log.info("登录态存储：Redis（重启不丢失）");
        } else {
            log.info("登录态存储：内存（无 Redis，重启后需重新登录）");
        }
    }

    public AuthContext create(String username, String displayName, String role, String roleName,
                              Set<String> permissions) {
        String token = java.util.UUID.randomUUID().toString().replace("-", "");
        AuthContext ctx = new AuthContext(token, username, displayName, role, roleName,
                Set.copyOf(permissions), Instant.now().plus(ttl));
        put(token, ctx);
        return ctx;
    }

    /** 校验并滑动续期 */
    public Optional<AuthContext> get(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        AuthContext ctx = read(token);
        if (ctx == null) {
            return Optional.empty();
        }
        AuthContext renewed = new AuthContext(ctx.token(), ctx.username(), ctx.displayName(),
                ctx.role(), ctx.roleName(), ctx.permissions(), Instant.now().plus(ttl));
        put(token, renewed);
        return Optional.of(renewed);
    }

    public void remove(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        if (redis != null) {
            redis.delete(KEY_PREFIX + token);
        }
        memory.remove(token);
    }

    /** 原地更新登录态（如显示名变更）：token/TTL/权限保持不变 */
    public void replace(AuthContext ctx) {
        put(ctx.token(), ctx);
    }

    public int size() {
        return redis != null ? redis.keys(KEY_PREFIX + "*").size() : memory.size();
    }

    @Scheduled(fixedDelay = 300_000)
    public void prune() {
        if (redis == null) {
            Instant now = Instant.now();
            memory.entrySet().removeIf(e -> e.getValue().expiresAt().isBefore(now));
        }
        // Redis 模式由 TTL 自动过期
    }

    private void put(String token, AuthContext ctx) {
        if (redis != null) {
            try {
                redis.opsForValue().set(KEY_PREFIX + token, json.writeValueAsString(ctx), ttl);
                return;
            } catch (Exception e) {
                log.warn("Redis 写入失败，回退内存: {}", e.getMessage());
            }
        }
        memory.put(token, ctx);
    }

    private AuthContext read(String token) {
        if (redis != null) {
            try {
                String body = redis.opsForValue().get(KEY_PREFIX + token);
                if (body != null) {
                    return json.readValue(body, AuthContext.class);
                }
            } catch (Exception e) {
                log.warn("Redis 读取失败: {}", e.getMessage());
            }
        }
        return memory.get(token); // Redis 未启用或写入时曾回退
    }
}
