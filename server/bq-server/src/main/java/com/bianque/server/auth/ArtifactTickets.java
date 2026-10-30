package com.bianque.server.auth;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * 产物查看票据：一次性/60s/单工件，专供同源 iframe 渲染火焰图 HTML。
 * 背景：iframe 无法携带 Bearer 头，而 fetch+blob 载荷会继承页面 CSP
 * （frame-src 不含 blob:、blob 文档又继承 script-src 禁内联脚本）被浏览器整体拦截——
 * 改为登录后签发短时票据，iframe 以同源 URL（?bt=票据）加载。票据用后即焚，泄漏影响面极小。
 */
@Service
public class ArtifactTickets {

    private static final Duration TTL = Duration.ofSeconds(60);

    private record Ticket(long artifactId, long expireAt) {
    }

    private final Map<String, Ticket> tickets = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    /** 为工件签发查看票据 */
    public String issue(long artifactId) {
        byte[] buf = new byte[32];
        random.nextBytes(buf);
        String ticket = HexFormat.of().formatHex(buf);
        tickets.put(ticket, new Ticket(artifactId, System.currentTimeMillis() + TTL.toMillis()));
        if (tickets.size() > 256) {
            long now = System.currentTimeMillis();
            tickets.values().removeIf(t -> t.expireAt() < now);
        }
        return ticket;
    }

    /** 校验并消费票据（一次性：无论是否匹配都移除，防重放猜测） */
    public boolean consume(String ticket, long artifactId) {
        Ticket t = tickets.remove(ticket);
        return t != null && t.artifactId() == artifactId && t.expireAt() > System.currentTimeMillis();
    }
}
