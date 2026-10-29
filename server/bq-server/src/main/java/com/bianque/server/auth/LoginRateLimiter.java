package com.bianque.server.auth;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * 登录失败速率限制（内存滑动窗口，补掉 v1.0 已知边界）：同「账号+IP」60s 内失败 ≥5 次
 * 锁定 5 分钟（429）。仅内存态，重启即清；成功登录清除该键。
 */
@Component
public class LoginRateLimiter {

    static final int MAX_FAILS = 5;
    static final long WINDOW_MS = 60_000;
    static final long LOCK_MS = 300_000;

    private static final class Window {
        final ArrayDeque<Long> fails = new ArrayDeque<>();
        volatile long lockedUntil;
    }

    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    /** 键：username@ip */
    public void check(String key) {
        Window w = windows.get(key);
        if (w == null) {
            return;
        }
        long remain = w.lockedUntil - System.currentTimeMillis();
        if (remain > 0) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "登录尝试过于频繁，请 " + Math.max(1, remain / 1000 / 60 + 1) + " 分钟后再试");
        }
    }

    public void onFailure(String key) {
        Window w = windows.computeIfAbsent(key, k -> new Window());
        synchronized (w) {
            long now = System.currentTimeMillis();
            w.fails.addLast(now);
            prune(w, now);
            if (w.fails.size() >= MAX_FAILS) {
                w.lockedUntil = now + LOCK_MS;
                w.fails.clear();
            }
        }
    }

    public void onSuccess(String key) {
        windows.remove(key);
    }

    private void prune(Window w, long now) {
        Iterator<Long> it = w.fails.iterator();
        while (it.hasNext()) {
            if (now - it.next() > WINDOW_MS) {
                it.remove();
            } else {
                break;
            }
        }
    }

    /** 当前是否锁定（测试/展示用） */
    public boolean locked(String key) {
        Window w = windows.get(key);
        return w != null && w.lockedUntil > Instant.now().toEpochMilli();
    }
}
