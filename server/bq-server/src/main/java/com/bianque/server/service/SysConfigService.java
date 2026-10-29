package com.bianque.server.service;

import com.bianque.core.diag.CommandAllowlist;
import com.bianque.server.repo.AuditTrail;
import com.bianque.server.store.SysConfigEntity;
import com.bianque.server.store.SysConfigJpa;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 平台参数中心：注册表定义可配置项（键/默认值/类型/范围/生效语义），启动播种入库；
 * 读取走内存缓存（写后失效，免重启生效），DB 缺行回落默认值。
 * 红线项（端口绑定/强制认证/审计开关/高危黑名单内置基线）不进注册表，天然不可配；
 * 黑名单追加项（仅可加严）进注册表，页面管理。
 */
@Service
public class SysConfigService {

    private static final Logger log = LoggerFactory.getLogger(SysConfigService.class);

    /** 参数注册表条目（valueType 目前支持 int；effect 为页面展示的生效语义文案） */
    public record ParamSpec(String key, String defaultValue, String valueType, String category,
                            String displayName, String description, String effect, int min, int max) {
    }

    /** 全部可配置参数。新增参数：在此登记 + 使用方改为 config 读数即可。 */
    private static final List<ParamSpec> REGISTRY = List.of(
            // 上限 120s：Agent 通道等待为 命令超时+30s，前端 executeDiag 预算 150s，三者须保持闭环防假超时
            new ParamSpec("diag.command-timeout-seconds", "60", "int", "诊断执行",
                    "命令执行超时（秒）", "单条诊断命令从下发到结果回传的最长等待", "即时（下一条命令生效）", 5, 120),
            // 高危黑名单追加项（仅可加严）：内置基线 ognl/redefine/retransform/mc/groovy/heapdump 不在注册表、不可削减
            new ParamSpec("diag.blocked-extra-commands", "", "list", "诊断执行",
                    "高危黑名单追加命令", "在内置高危黑名单之上追加拦截的命令名（逗号/空格分隔），对人工诊断、AI 会诊与模板保存统一生效", "即时（下一条命令生效）", 0, 0),
            new ParamSpec("diag.session-ttl-minutes", "30", "int", "诊断执行",
                    "会话自动回收（分钟）", "诊断会话开启后经过该时长自动 reset+stop 回收", "新会话生效（存量会话按开启时值）", 5, 480),
            new ParamSpec("agent.heartbeat-interval-seconds", "5", "int", "节点探针",
                    "探针心跳间隔（秒）", "服务端下发给探针的心跳节奏（v1.1.0+ 探针动态跟随；旧版探针忽略）", "下一心跳生效", 2, 60),
            new ParamSpec("agent.online-window-seconds", "20", "int", "节点探针",
                    "在线判定窗口（秒）", "超过该时长未收到心跳即判定节点离线；须不小于心跳间隔的 3 倍", "即时", 6, 600),
            new ParamSpec("auth.token-ttl-hours", "8", "int", "认证安全",
                    "登录令牌有效期（小时）", "登录态滑动过期时长", "下次续期生效", 1, 168),
            new ParamSpec("checkup.deadline-seconds", "150", "int", "巡检体检",
                    "巡检总时限（秒）", "一轮全量巡检的整体截止时间", "下次巡检生效", 30, 1200),
            new ParamSpec("checkup.instance-timeout-seconds", "90", "int", "巡检体检",
                    "单实例巡检时限（秒）", "等待单个实例巡检完成的最长时间", "下次巡检生效", 10, 1200),
            new ParamSpec("data.artifact-retention-days", "7", "int", "数据保留",
                    "诊断工件保留（天）", "性能剖析产物等工件超过该天数自动清理", "下次清理任务生效（每 6 小时）", 1, 365),
            new ParamSpec("data.command-retention-days", "14", "int", "数据保留",
                    "命令单保留（天）", "已完成/失败的节点命令单超过该天数自动清理", "下次清理任务生效（每 6 小时）", 1, 90));

    private final Optional<SysConfigJpa> repo; // 无 JPA 环境（单测）回退纯内存
    private final AuditTrail audit;
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public SysConfigService(ObjectProvider<SysConfigJpa> repo, AuditTrail audit) {
        this.repo = Optional.ofNullable(repo == null ? null : repo.getIfAvailable());
        this.audit = audit;
    }

    /** 单测/无 DB 场景：纯内存参数（默认值起，可改，不落库不审计） */
    public static SysConfigService inMemory(AuditTrail audit) {
        return new SysConfigService(null, audit);
    }

    public static List<ParamSpec> registry() {
        return REGISTRY;
    }

    @PostConstruct
    void seed() {
        repo.ifPresent(jpa -> {
            for (ParamSpec s : REGISTRY) {
                if (!jpa.existsById(s.key())) {
                    jpa.save(new SysConfigEntity(s.key(), s.defaultValue()));
                }
            }
        });
        log.info("参数中心就绪：{} 项可配置参数", REGISTRY.size());
    }

    // ---- 读取（业务接线用）----

    public int intValue(String key) {
        return (int) longValue(key);
    }

    /** list 型参数当前值（逗号分隔 token；int 型参数恒返回空列表） */
    public List<String> listValue(String key) {
        String raw = effective(key);
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(",")).map(String::trim).filter(t -> !t.isEmpty()).toList();
    }

    public long longValue(String key) {
        ParamSpec spec = spec(key);
        String raw = effective(key);
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            log.warn("参数 {} 值非法（{}），回落默认 {}", key, raw, spec.defaultValue());
            return Long.parseLong(spec.defaultValue());
        }
    }

    /** 当前生效值（DB 值优先，缺行/缺省回落注册表默认） */
    private String effective(String key) {
        return cache.computeIfAbsent(key, k -> repo
                .flatMap(jpa -> jpa.findById(k))
                .map(e -> e.configValue)
                .orElseGet(() -> spec(k).defaultValue()));
    }

    public ParamSpec spec(String key) {
        return REGISTRY.stream().filter(s -> s.key().equals(key)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "未知参数: " + key));
    }

    // ---- 管理（/api/admin/config）----

    /** 参数清单（含当前值/是否已自定义/最近修改人） */
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ParamSpec s : REGISTRY) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", s.key());
            m.put("value", effective(s.key()));
            m.put("defaultValue", s.defaultValue());
            m.put("valueType", s.valueType());
            m.put("min", s.min());
            m.put("max", s.max());
            m.put("category", s.category());
            m.put("displayName", s.displayName());
            m.put("description", s.description());
            m.put("effect", s.effect());
            m.put("customized", !effective(s.key()).equals(s.defaultValue()));
            repo.flatMap(jpa -> jpa.findById(s.key())).ifPresent(e -> {
                m.put("updatedBy", e.updatedBy);
                m.put("updatedAt", e.updatedAt);
            });
            out.add(m);
        }
        return out;
    }

    /** 修改参数：类型/范围/关联校验 → 落库 → 失效缓存 → 审计 */
    public Map<String, Object> update(String key, String value, String operator) {
        ParamSpec s = spec(key);
        if ("list".equals(s.valueType())) {
            return updateList(s, value, operator);
        }
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "参数值不能为空");
        }
        long v;
        try {
            v = Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "参数须为整数: " + value);
        }
        if (v < s.min() || v > s.max()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "超出允许范围 [" + s.min() + ", " + s.max() + "]: " + v);
        }
        checkRelations(key, v);
        save(key, String.valueOf(v), operator);
        audit.append("管理", operator, "-", "参数修改 " + key + " = " + v, 0, "ok");
        return view(key);
    }

    /** 追加黑名单条目上限（防误粘贴/滥用；仅加严方向，上限不影响内置基线） */
    private static final int MAX_EXTRA_BLOCKED = 50;

    /**
     * list 型参数（高危黑名单追加项）：token 规范化/校验 → 去重 → 逗号串落库 → 审计；空值 = 清空追加项。
     * 只能加严：命中内置基线的 token 拒绝（已拦截，无需追加），非法 token 拒绝。
     */
    private Map<String, Object> updateList(ParamSpec s, String value, String operator) {
        if (value == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "参数值不能为空");
        }
        List<String> tokens = Arrays.stream(value.split("[,，;；\\s\\u3000]+"))
                .map(t -> t.trim().toLowerCase()).filter(t -> !t.isEmpty()).toList();
        for (String t : tokens) {
            if (!t.matches("[a-z][a-z0-9\\-]*")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "命令名非法（须为小写字母开头的命令 token）: " + t);
            }
            if (CommandAllowlist.DEFAULT_BLOCKED.contains(t)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "已在内置黑名单中，无需追加: " + t);
            }
        }
        List<String> deduped = tokens.stream().distinct().toList();
        if (deduped.size() > MAX_EXTRA_BLOCKED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "追加命令至多 " + MAX_EXTRA_BLOCKED + " 条");
        }
        String joined = String.join(",", deduped);
        save(s.key(), joined, operator);
        audit.append("管理", operator, "-", "高危黑名单追加项修改 = [" + joined + "]", 0, "ok");
        return view(s.key());
    }

    /** 恢复默认值 */
    public Map<String, Object> reset(String key, String operator) {
        ParamSpec s = spec(key);
        save(key, s.defaultValue(), operator);
        audit.append("管理", operator, "-", "参数恢复默认 " + key + " = " + s.defaultValue(), 0, "ok");
        return view(key);
    }

    /** 关联约束：写值前对当前其他参数校验 */
    private void checkRelations(String key, long v) {
        if ("agent.online-window-seconds".equals(key)
                && v < 3L * longValue("agent.heartbeat-interval-seconds")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "在线判定窗口须不小于心跳间隔的 3 倍（当前心跳间隔 "
                            + longValue("agent.heartbeat-interval-seconds") + "s）");
        }
        if ("agent.heartbeat-interval-seconds".equals(key)
                && longValue("agent.online-window-seconds") < 3L * v) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "心跳间隔须不大于在线判定窗口的 1/3（当前在线判定窗口 "
                            + longValue("agent.online-window-seconds") + "s）");
        }
        if ("checkup.deadline-seconds".equals(key)
                && v < longValue("checkup.instance-timeout-seconds")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "巡检总时限须不小于单实例时限");
        }
        if ("checkup.instance-timeout-seconds".equals(key)
                && longValue("checkup.deadline-seconds") < v) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "单实例时限须不大于巡检总时限");
        }
    }

    private void save(String key, String value, String operator) {
        SysConfigEntity e = repo.flatMap(jpa -> jpa.findById(key)).orElseGet(() -> new SysConfigEntity(key, value));
        e.configValue = value;
        e.updatedBy = operator;
        e.updatedAt = Instant.now();
        repo.ifPresent(jpa -> jpa.save(e));
        cache.put(key, value); // 写后失效（直接覆盖为新值，免重启生效）
    }

    private Map<String, Object> view(String key) {
        return list().stream().filter(m -> key.equals(m.get("key"))).findFirst().orElseThrow();
    }
}
