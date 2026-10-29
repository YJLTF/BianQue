package com.bianque.server.service;

import com.bianque.server.repo.AuditTrail;
import com.bianque.server.store.DiagTemplateEntity;
import com.bianque.server.store.DiagTemplateJpa;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 模板中心：诊断场景 / 巡检体检 / 急诊取证 三类模板统一管理。
 * 内置模板启动播种（可停用可复制不可删除）；体检与急诊从模板读取命令序列，行为与 v1.0 保持一致。
 */
@Service
public class TemplateService {

    private static final Logger log = LoggerFactory.getLogger(TemplateService.class);
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([^{}]+)}}");

    /** 模板命令项：name 为展示名（急诊步骤名/诊断提示），command 可含 {{占位符}} */
    public record TemplateItem(String name, String command) {
    }

    private final DiagTemplateJpa repo;
    private final AuditTrail audit;
    private final ObjectMapper json;
    private final BlacklistService blacklist;

    public TemplateService(DiagTemplateJpa repo, AuditTrail audit, ObjectMapper json, BlacklistService blacklist) {
        this.repo = repo;
        this.audit = audit;
        this.json = json;
        this.blacklist = blacklist;
    }

    /** 内置模板：诊断台 6 场景 + 默认体检 + 默认急诊（与 v1.0 硬编码行为一致） */
    private static List<DiagTemplateEntity> builtIns() {
        List<DiagTemplateEntity> out = new ArrayList<>();
        out.add(diag(1, "慢接口排查", "接口", "trace {{类名}} {{方法名}} '#cost>100'"));
        out.add(diag(2, "CPU 飙高", "CPU", "thread -n 5"));
        out.add(diag(3, "内存水位", "内存", "memory"));
        out.add(diag(4, "JVM 概览", "通用", "jvm"));
        out.add(diag(5, "实时面板", "通用", "dashboard -n 1"));
        out.add(diag(6, "接口观测", "接口", "watch {{类名}} {{方法名}} '{params,returnObj}' -n 100"));
        out.add(new DiagTemplateEntity("默认体检", DiagTemplateEntity.KIND_CHECKUP, "体检",
                itemsJson(List.of(new TemplateItem("JVM / GC 统计", "jvm"),
                        new TemplateItem("内存水位", "memory"),
                        new TemplateItem("最忙线程 + 死锁检测", "thread"))),
                null, true, 1, "system"));
        out.add(new DiagTemplateEntity("默认急诊取证", DiagTemplateEntity.KIND_RESCUE, "急诊",
                itemsJson(List.of(new TemplateItem("最忙线程 + 死锁检测", "thread -n 5"),
                        new TemplateItem("内存水位", "memory"),
                        new TemplateItem("JVM / GC 统计", "jvm"))),
                null, true, 1, "system"));
        return out;
    }

    private static DiagTemplateEntity diag(int sort, String name, String scene, String command) {
        return new DiagTemplateEntity(name, DiagTemplateEntity.KIND_DIAG, scene,
                itemsJson(List.of(new TemplateItem(command, command))), null, true, sort, "system");
    }

    private static String itemsJson(List<TemplateItem> items) {
        try {
            return new ObjectMapper().writeValueAsString(items);
        } catch (Exception e) {
            throw new IllegalStateException("模板命令序列序列化失败", e);
        }
    }

    @PostConstruct
    void seed() {
        for (DiagTemplateEntity t : builtIns()) {
            boolean exists = repo.findByKindOrderBySortAscIdAsc(t.kind).stream()
                    .anyMatch(e -> e.name.equals(t.name) && e.builtIn);
            if (!exists) {
                repo.save(t);
            }
        }
        log.info("模板中心就绪：内置 8 模板已播种（缺才补）");
    }

    // ---- 查询 ----

    /** 管理列表（含停用） */
    public List<Map<String, Object>> listAll(String kind) {
        return toViews(kind == null || kind.isBlank()
                ? repo.findAll().stream().sorted((a, b) -> a.id.compareTo(b.id)).toList()
                : repo.findByKindOrderBySortAscIdAsc(kind));
    }

    /** 诊断台可用列表（仅启用） */
    public List<Map<String, Object>> listEnabled(String kind) {
        return toViews(kind == null || kind.isBlank()
                ? repo.findAll().stream().filter(e -> e.enabled)
                        .sorted((a, b) -> a.id.compareTo(b.id)).toList()
                : repo.findByKindAndEnabledTrueOrderBySortAscIdAsc(kind));
    }

    private List<Map<String, Object>> toViews(List<DiagTemplateEntity> list) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (DiagTemplateEntity e : list) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.id);
            m.put("name", e.name);
            m.put("kind", e.kind);
            m.put("scene", e.scene);
            m.put("items", items(e));
            m.put("placeholders", placeholders(e.itemsJson));
            m.put("bindAppId", e.bindAppId);
            m.put("builtIn", e.builtIn);
            m.put("enabled", e.enabled);
            m.put("sort", e.sort);
            m.put("createdBy", e.createdBy);
            out.add(m);
        }
        return out;
    }

    private List<TemplateItem> items(DiagTemplateEntity e) {
        try {
            return json.readValue(e.itemsJson, new TypeReference<List<TemplateItem>>() {
            });
        } catch (Exception ex) {
            return List.of();
        }
    }

    // ---- 变更 ----

    public record TemplateReq(String name, String kind, String scene, List<TemplateItem> items,
                              String bindAppId, Boolean enabled) {
    }

    public Map<String, Object> create(TemplateReq req, String operator) {
        validate(req);
        DiagTemplateEntity e = new DiagTemplateEntity(req.name().trim(), req.kind(), req.scene() == null ? "自定义" : req.scene(),
                itemsJson(req.items()), req.bindAppId(), false,
                req.kind().equals(DiagTemplateEntity.KIND_DIAG) ? 100 : 10, operator);
        e.updatedAt = Instant.now();
        e = repo.save(e);
        audit.append("管理", operator, "-", "模板创建 " + e.name + "（" + e.kind + "）", 0, "ok");
        return view(e.id);
    }

    public Map<String, Object> update(long id, TemplateReq req, String operator) {
        DiagTemplateEntity e = repo.findById(id).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "模板不存在"));
        validate(req);
        e.name = req.name().trim();
        e.scene = req.scene() == null ? e.scene : req.scene();
        e.itemsJson = itemsJson(req.items());
        e.bindAppId = req.bindAppId();
        if (req.enabled() != null) {
            e.enabled = req.enabled();
        }
        e.updatedAt = Instant.now();
        repo.save(e);
        audit.append("管理", operator, "-", "模板更新 " + e.name + "（" + e.kind + "）", 0, "ok");
        return view(e.id);
    }

    /** 停用/启用（内置模板不可删除，只可停用） */
    public Map<String, Object> enable(long id, boolean enabled, String operator) {
        DiagTemplateEntity e = repo.findById(id).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "模板不存在"));
        e.enabled = enabled;
        e.updatedAt = Instant.now();
        repo.save(e);
        audit.append("管理", operator, "-", (enabled ? "模板启用 " : "模板停用 ") + e.name, 0, "ok");
        return view(e.id);
    }

    public Map<String, Object> delete(long id, String operator) {
        DiagTemplateEntity e = repo.findById(id).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "模板不存在"));
        if (e.builtIn) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "内置模板不可删除（可停用或复制后修改）");
        }
        repo.delete(e);
        audit.append("管理", operator, "-", "模板删除 " + e.name, 0, "ok");
        return Map.of("deleted", e.name);
    }

    private void validate(TemplateReq req) {
        if (req.name() == null || req.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "模板名称不能为空");
        }
        if (!List.of(DiagTemplateEntity.KIND_DIAG, DiagTemplateEntity.KIND_CHECKUP, DiagTemplateEntity.KIND_RESCUE)
                .contains(req.kind())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "模板类型须为 diag/checkup/rescue");
        }
        if (req.items() == null || req.items().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "命令序列不能为空");
        }
        for (TemplateItem it : req.items()) {
            if (it.command() == null || it.command().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "命令不能为空");
            }
            String head = it.command().trim().split("\\s+")[0].toLowerCase();
            if (!head.matches("[a-z][a-z0-9\\-]*")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "命令首 token 非法: " + it.command());
            }
            // 保存即校验（fail fast）：生效黑名单 = 内置基线 ∪ 页面追加项，命中即拒绝
            if (!blacklist.isAllowed(head)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "命令命中高危黑名单: " + head);
            }
        }
    }

    // ---- 占位符 ----

    /** 命令中的 {{占位符}} 键集合（按出现顺序去重） */
    public static LinkedHashSet<String> placeholders(String itemsJson) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        Matcher m = PLACEHOLDER.matcher(itemsJson == null ? "" : itemsJson);
        while (m.find()) {
            keys.add(m.group(1).trim());
        }
        return keys;
    }

    /** 占位符渲染：{{key}} → 值（缺值保留原样并返回缺值清单） */
    public static String render(String command, Map<String, String> params) {
        Matcher m = PLACEHOLDER.matcher(command);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String key = m.group(1).trim();
            String val = params == null ? null : params.get(key);
            m.appendReplacement(sb, Matcher.quoteReplacement(val == null || val.isBlank() ? m.group(0) : val));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    public static boolean hasPlaceholder(String command) {
        return PLACEHOLDER.matcher(command).find();
    }

    public static Set<String> placeholdersOf(String command) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        Matcher m = PLACEHOLDER.matcher(command);
        while (m.find()) {
            keys.add(m.group(1).trim());
        }
        return keys;
    }

    // ---- 体检 / 急诊消费 ----

    /** 体检命令集：首个启用的体检模板，兜底 jvm/memory/thread（与 v1.0 一致） */
    public List<String> resolveCheckupCommands() {
        for (DiagTemplateEntity e : repo.findByKindAndEnabledTrueOrderBySortAscIdAsc(DiagTemplateEntity.KIND_CHECKUP)) {
            List<String> cmds = items(e).stream().map(TemplateItem::command).toList();
            if (!cmds.isEmpty()) {
                return cmds;
            }
        }
        return List.of("jvm", "memory", "thread");
    }

    /** 急诊取证序列：首个启用的急诊模板（步骤名 + 命令），兜底 v1.0 三步 */
    public List<TemplateItem> resolveRescueSteps() {
        for (DiagTemplateEntity e : repo.findByKindAndEnabledTrueOrderBySortAscIdAsc(DiagTemplateEntity.KIND_RESCUE)) {
            List<TemplateItem> its = items(e);
            if (!its.isEmpty()) {
                return its;
            }
        }
        return List.of(new TemplateItem("最忙线程 + 死锁检测", "thread -n 5"),
                new TemplateItem("内存水位", "memory"),
                new TemplateItem("JVM / GC 统计", "jvm"));
    }

    private Map<String, Object> view(long id) {
        return listAll(null).stream().filter(m -> ((Number) m.get("id")).longValue() == id).findFirst().orElseThrow();
    }
}
