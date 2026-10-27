package com.bianque.server.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 不透明令牌仓储：token → 登录态（滑动过期，默认 8h）。重启失效需重新登录（S2 可换 Redis/JWT）。
 */
@Component
public class TokenStore {

    private final Map<String, AuthContext> tokens = new ConcurrentHashMap<>();
    private final Duration ttl;

    public TokenStore(@Value("${bianque.auth.token-ttl-hours:8}") long ttlHours) {
        this.ttl = Duration.ofHours(ttlHours);
    }

    public AuthContext create(String username, String displayName, String role, String roleName,
                              java.util.Set<String> permissions) {
        String token = java.util.UUID.randomUUID().toString().replace("-", "");
        AuthContext ctx = new AuthContext(token, username, displayName, role, roleName,
                Set.copyOf(permissions), Instant.now().plus(ttl));
        tokens.put(token, ctx);
        return ctx;
    }

    /** 校验并滑动续期 */
    public Optional<AuthContext> get(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        AuthContext ctx = tokens.get(token);
        if (ctx == null) {
            return Optional.empty();
        }
        if (ctx.expiresAt().isBefore(Instant.now())) {
            tokens.remove(token);
            return Optional.empty();
        }
        AuthContext renewed = new AuthContext(ctx.token(), ctx.username(), ctx.displayName(),
                ctx.role(), ctx.roleName(), ctx.permissions(), Instant.now().plus(ttl));
        tokens.put(token, renewed);
        return Optional.of(renewed);
    }

    public void remove(String token) {
        tokens.remove(token);
    }

    public int size() {
        return tokens.size();
    }

    @Scheduled(fixedDelay = 300_000)
    public void prune() {
        Instant now = Instant.now();
        tokens.entrySet().removeIf(e -> e.getValue().expiresAt().isBefore(now));
    }
}
