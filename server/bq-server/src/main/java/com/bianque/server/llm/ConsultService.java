package com.bianque.server.llm;

import com.bianque.core.diag.CommandAllowlist;
import com.bianque.core.diag.ResultParsers;
import com.bianque.server.model.Types.CheckupRow;
import com.bianque.server.model.Types.Instance;
import com.bianque.server.repo.AuditTrail;
import com.bianque.server.repo.FleetRepository;
import com.bianque.server.service.CheckupService;
import com.bianque.server.service.DiagService;
import com.bianque.server.store.AiToolRunEntity;
import com.bianque.server.store.AiToolRunJpa;
import com.bianque.server.store.LlmConfigEntity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * AI 会诊：选中实例 + 自然语言需求 → 模型经标准 function calling 调平台工具（列实例/执行诊断/体检/查文档/看告警）
 * → 基于真实输出给出结论。安全边界：
 * - 命令全量过 CommandAllowlist：黑名单直接拒绝且不可审批放行；增强命令（watch/trace/tt/monitor/stack/profiler/jfr）
 *   须用户在审批卡批准（60s 超时默认拒绝）；
 * - 步数上限 8（服务端强制）；全部工具调用落 ai_tool_run + 审计哈希链；
 * - 上游端点不支持 tools 时明确降级提示回退纯问答。
 */
@Service
public class ConsultService {

    private static final Logger log = LoggerFactory.getLogger(ConsultService.class);
    static final int MAX_STEPS = 8;
    static final long CONFIRM_WAIT_SECONDS = 60;
    private static final int TOOL_RESULT_CHARS = 2_500;

    public record ToolStep(int step, String tool, String argsJson, String command,
                           String decision, String status, String summary, long costMs) {
    }

    public interface ConsultListener {
        void onTool(ToolStep step);

        void onConfirmRequired(int step, String tool, String argsJson, String command);

        void onReasoning(String delta);

        void onContent(String delta);

        void onDone(String content, List<ToolStep> steps);

        void onError(String message);
    }

    private static final class Pending {
        final int step;
        final CountDownLatch latch = new CountDownLatch(1);
        volatile Boolean approved;

        Pending(int step) {
            this.step = step;
        }
    }

    private final LlmService llm;
    private final FleetRepository fleet;
    private final DiagService diag;
    private final CheckupService checkup;
    private final AuditTrail audit;
    private final AiToolRunJpa toolRuns;
    private final ObjectMapper json;
    private final com.bianque.server.service.BlacklistService blacklist;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final Map<String, Pending> pendings = new ConcurrentHashMap<>();
    private final ExecutorService workers = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "ai-consult");
        t.setDaemon(true);
        return t;
    });

    public ConsultService(LlmService llm, FleetRepository fleet, DiagService diag, CheckupService checkup,
                          AuditTrail audit, AiToolRunJpa toolRuns, ObjectMapper json,
                          com.bianque.server.service.BlacklistService blacklist) {
        this.llm = llm;
        this.fleet = fleet;
        this.diag = diag;
        this.checkup = checkup;
        this.audit = audit;
        this.toolRuns = toolRuns;
        this.json = json;
        this.blacklist = blacklist;
    }

    private static final String SYSTEM_PROMPT = """
            你是扁鹊平台的线上会诊医生。用户已选定一个线上实例并用自然语言描述问题，你通过工具获取真实数据再下结论。
            工作法：
            1) 先用 list_instances/describe_instance 确认目标实例存在且在线（用户已给实例时跳过）；
            2) 按症状选只读命令：CPU 高→thread -n 5；内存/GC→memory、jvm；接口慢→trace/watch（增强命令会弹出审批，谨慎使用）；
            3) 每步命令后基于输出决定下一步，不要盲目堆命令；至多 8 步；
            4) 最终结论必须：先结论、再证据（引用命令输出关键行）、最后给处置建议；建议仅限只读命令与配置/扩容类动作；
            5) 信息不足时说明还缺什么，不要臆测。全程简体中文。""";

    /** 工具定义（OpenAI function calling 形态） */
    private List<Map<String, Object>> tools() {
        List<Map<String, Object>> out = new ArrayList<>();
        out.add(tool("list_instances", "列出平台纳管的实例清单（id/应用/主机/在线状态/通道）",
                Map.of("type", "object", "properties", Map.of(
                        "appId", Map.of("type", "string", "description", "按应用过滤（可选）")))));
        out.add(tool("describe_instance", "查看指定实例详情（主机/主类/通道/会话状态）",
                Map.of("type", "object", "properties", Map.of(
                        "instanceId", Map.of("type", "string"), "required", List.of("instanceId")))));
        out.add(tool("run_diag_command", "在指定实例上执行一条只读 Arthas 诊断命令并返回输出。黑名单命令（内置高危 ognl/redefine/retransform/mc/groovy/heapdump + 管理员追加项）一律拒绝；watch/trace/tt/monitor/stack/profiler/jfr 为增强命令需用户审批",
                Map.of("type", "object", "properties", Map.of(
                        "instanceId", Map.of("type", "string"),
                        "command", Map.of("type", "string", "description", "如 thread -n 5 / memory / jvm"),
                        "required", List.of("instanceId", "command")))));
        out.add(tool("run_checkup", "对指定实例跑一次巡检体检（jvm/memory/thread 阈值打分），返回健康分与扣分项",
                Map.of("type", "object", "properties", Map.of(
                        "instanceId", Map.of("type", "string"), "required", List.of("instanceId")))));
        out.add(tool("search_docs", "检索 Arthas 官方文档知识库（向量+关键词混合），返回相关页片段",
                Map.of("type", "object", "properties", Map.of(
                        "query", Map.of("type", "string"), "required", List.of("query")))));
        out.add(tool("recent_alerts", "查看最近的告警事件",
                Map.of("type", "object", "properties", Map.of(
                        "limit", Map.of("type", "integer")))));
        return out;
    }

    private Map<String, Object> tool(String name, String desc, Object parameters) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("name", name);
        f.put("description", desc);
        f.put("parameters", parameters);
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("type", "function");
        t.put("function", f);
        return t;
    }

    /** 发起会诊（异步执行，事件经 listener 推送）；返回 consultId */
    public String start(String operator, String instanceId, String question,
                        List<LlmService.ChatTurn> history, ConsultListener listener) {
        String consultId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        workers.submit(() -> {
            try {
                run(operator, consultId, instanceId, question, history, listener);
            } catch (Exception e) {
                log.warn("AI 会诊异常: {}", e.toString());
                listener.onError("会诊执行失败：" + e.getMessage());
            }
        });
        return consultId;
    }

    /** 审批决策：返回是否登记成功（无对应待审批步骤返回 false） */
    public boolean decide(String consultId, int step, boolean approved) {
        Pending p = pendings.get(consultId + ":" + step);
        if (p == null) {
            return false;
        }
        p.approved = approved;
        p.latch.countDown();
        return true;
    }

    // ---- 主循环 ----

    void run(String operator, String consultId, String instanceId, String question,
             List<LlmService.ChatTurn> history, ConsultListener listener) {
        LlmConfigEntity c = llm.config();
        if (!llm.isConfigured(c)) {
            listener.onError("尚未配置模型接入：请在「AI 问诊 → 模型接入」填写端点与模型");
            return;
        }
        List<Object> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", SYSTEM_PROMPT));
        if (history != null) {
            for (LlmService.ChatTurn t : history.subList(Math.max(0, history.size() - 6), history.size())) {
                messages.add(Map.of("role", t.role(), "content", cap(t.content(), 4000)));
            }
        }
        String userQ = question + (instanceId == null || instanceId.isBlank() ? ""
                : "\n\n（会诊对象实例：" + instanceId + "）");
        messages.add(Map.of("role", "user", "content", userQ));

        List<ToolStep> steps = new ArrayList<>();
        for (int step = 1; step <= MAX_STEPS; step++) {
            JsonNode msg = callUpstream(c, messages, true);
            if (msg == null) {
                listener.onError("上游端点调用失败，请稍后重试或检查模型接入配置");
                return;
            }
            JsonNode toolCalls = msg.path("tool_calls");
            if (!toolCalls.isArray() || toolCalls.isEmpty()) {
                finish(listener, msg, steps);
                return;
            }
            // assistant 带工具调用入上下文
            messages.add(json.valueToTree(Map.of("role", "assistant", "content",
                    msg.path("content").asText(""), "tool_calls", toolCalls)));
            for (JsonNode tc : toolCalls) {
                String tool = tc.path("function").path("name").asText("");
                String argsJson = tc.path("function").path("arguments").asText("{}");
                String callId = tc.path("id").asText("");
                ToolStep done = executeTool(operator, consultId, step, tool, argsJson, instanceId, listener);
                steps.add(done);
                String resultText = switch (done.status()) {
                    case "denied" -> "用户拒绝了该命令的执行。请改用只读命令（thread/memory/jvm/dashboard）继续分析。";
                    case "timeout" -> "审批超时未响应，命令未执行。请改用只读命令继续分析。";
                    case "blocked" -> "该命令命中平台高危黑名单，已被拦截且不可放行。请改用只读命令。";
                    case "ok" -> done.summary();
                    default -> "执行失败：" + done.summary();
                };
                Map<String, Object> toolMsg = new LinkedHashMap<>();
                toolMsg.put("role", "tool");
                toolMsg.put("tool_call_id", callId);
                toolMsg.put("content", resultText);
                messages.add(json.valueToTree(toolMsg));
            }
        }
        // 步数用尽：最后一次不带工具的收束请求
        JsonNode finalMsg = callUpstream(c, messages, false);
        finish(listener, finalMsg, steps);
    }

    private void finish(ConsultListener listener, JsonNode msg, List<ToolStep> steps) {
        if (msg == null) {
            listener.onError("上游端点调用失败（收束阶段）");
            return;
        }
        String reasoning = msg.path("reasoning_content").asText(
                msg.path("reasoning").asText(""));
        if (!reasoning.isBlank()) {
            listener.onReasoning(reasoning);
        }
        String content = msg.path("content").asText("");
        // 分块下发模拟打字机（会诊循环为非流式，最终答案切块推送）
        for (int i = 0; i < content.length(); i += 64) {
            listener.onContent(content.substring(i, Math.min(content.length(), i + 64)));
        }
        listener.onDone(content, steps);
    }

    /** 执行单个工具调用（含审批/黑名单/留痕/审计）；恒返回 ToolStep */
    private ToolStep executeTool(String operator, String consultId, int step, String tool,
                                 String argsJson, String defaultInstanceId, ConsultListener listener) {
        String instanceId = arg(argsJson, "instanceId", defaultInstanceId);
        String command = arg(argsJson, "command", "");
        String decision = AiToolRunEntity.DECISION_AUTO;
        String status = "fail";
        String summary = "";
        long begin = System.currentTimeMillis();

        try {
            switch (tool) {
                case "list_instances" -> {
                    List<Instance> online = fleet.allInstances().stream()
                            .filter(Instance::online).limit(20).toList();
                    final String app = arg(argsJson, "appId", null);
                    List<Instance> targets = app != null && !app.isBlank()
                            ? online.stream().filter(i -> i.appId() != null && i.appId().contains(app)).toList()
                            : online;
                    List<Map<String, Object>> rows = new ArrayList<>();
                    for (Instance i : targets) {
                        rows.add(Map.of("instanceId", i.instanceId(), "app", i.appId(),
                                "host", i.host() == null ? "" : i.host()));
                    }
                    status = "ok";
                    summary = "在线实例 " + rows.size() + " 个：" + json.writeValueAsString(rows);
                }
                case "describe_instance" -> {
                    Instance ins = fleet.find(instanceId).orElse(null);
                    if (ins == null) {
                        summary = "实例不存在或已下线：" + instanceId;
                    } else {
                        status = "ok";
                        summary = "实例详情：" + json.writeValueAsString(Map.of(
                                "instanceId", ins.instanceId(), "app", ins.appId(), "host", ins.host(),
                                "mainClass", ins.mainClass() == null ? "" : ins.mainClass(),
                                "online", ins.online(), "localAgent", ins.localAgent()));
                    }
                }
                case "run_diag_command" -> {
                    String head = command.trim().split("\\s+")[0].toLowerCase();
                    if (!CommandAllowlist.ENHANCED.contains(head)) {
                        // 黑名单或只读：黑名单拒绝（不可放行），只读直接执行
                        DiagResult0 r = runDiag(operator, instanceId, command);
                        decision = r.blocked() ? AiToolRunEntity.DECISION_BLOCKED : AiToolRunEntity.DECISION_AUTO;
                        status = r.blocked() ? "blocked" : (r.success() ? "ok" : "fail");
                        summary = r.summary();
                        if (r.blocked()) {
                            break;
                        }
                    } else {
                        // 增强命令：审批卡
                        listener.onConfirmRequired(step, tool, argsJson, command);
                        Pending p = new Pending(step);
                        pendings.put(consultId + ":" + step, p);
                        boolean decided = p.latch.await(CONFIRM_WAIT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
                        pendings.remove(consultId + ":" + step);
                        Boolean approved = decided ? p.approved : null;
                        if (approved == null) {
                            decision = AiToolRunEntity.DECISION_TIMEOUT;
                            summary = "审批超时（" + CONFIRM_WAIT_SECONDS + "s），命令未执行";
                        } else if (!approved) {
                            decision = AiToolRunEntity.DECISION_DENIED;
                            summary = "用户拒绝执行";
                        } else {
                            decision = AiToolRunEntity.DECISION_APPROVED;
                            DiagResult0 r = runDiag(operator, instanceId, command);
                            status = r.success() ? "ok" : "fail";
                            summary = r.summary();
                        }
                    }
                }
                case "run_checkup" -> {
                    CheckupRow row = checkup.checkupOne(instanceId, operator);
                    status = "ok";
                    summary = "健康分 " + row.score() + "，扣分项：" + String.join("；", row.deductions());
                }
                case "search_docs" -> {
                    String q = arg(argsJson, "query", "");
                    List<com.bianque.server.service.DocsService.DocHit> hits = llm.searchHybrid(q, 3);
                    List<Map<String, Object>> brief = hits.stream().map(h -> Map.of(
                            "slug", (Object) h.slug(), "title", (Object) h.title(),
                            "snippet", (Object) cap(h.snippet(), 600))).toList();
                    status = "ok";
                    summary = hits.isEmpty() ? "无命中" : json.writeValueAsString(brief);
                }
                case "recent_alerts" -> {
                    summary = "告警查询工具暂未接入会诊（计划 1.2）";
                }
                default -> {
                    summary = "未知工具：" + tool;
                }
            }
        } catch (Exception e) {
            summary = "工具执行异常：" + e.getMessage();
        }
        long cost = System.currentTimeMillis() - begin;
        if ("ok".equals(status)) {
            summary = cap(summary, TOOL_RESULT_CHARS);
        }
        ToolStep out = new ToolStep(step, tool, argsJson, command, decision, status, cap(summary, 400), cost);
        toolRuns.save(new AiToolRunEntity(consultId, step, tool, argsJson, command, decision, status,
                cap(summary, 500), cost, operator));
        if ("run_diag_command".equals(tool) || "run_checkup".equals(tool)) {
            audit.append("AI会诊", operator, instanceId, tool + " " + command, cost,
                    "ok".equals(status) ? "ok" : status);
        }
        listener.onTool(out);
        return out;
    }

    /** 黑名单判断 + 执行封装（生效黑名单 = 内置基线 ∪ 页面追加项，与人工诊断同口径） */
    private DiagResult0 runDiag(String operator, String instanceId, String command) {
        if (!blacklist.isAllowed(command)) {
            return new DiagResult0(false, true, "已拦截：命令命中高危黑名单 " + blacklist.effective());
        }
        try {
            var r = diag.execute(instanceId, command, "AI会诊", operator);
            String text = r.success() ? cap(r.output(), TOOL_RESULT_CHARS) : r.error();
            // 结构化摘要附加（thread/dashboard 等可解析时给模型更紧凑的视图）
            Map<String, Object> metrics = ResultParsers.parse(command, r.output());
            if (metrics != null) {
                text = text + "\n（结构化指标：" + cap(json.writeValueAsString(metrics), 800) + "）";
            }
            return new DiagResult0(r.success(), false, "success=" + r.success() + " cost=" + r.costMs() + "ms\n" + text);
        } catch (Exception e) {
            return new DiagResult0(false, false, "执行失败：" + e.getMessage());
        }
    }

    private record DiagResult0(boolean success, boolean blocked, String summary) {
    }

    // ---- 上游调用 ----

    /** 非流式调用上游；tools=false 时不带工具定义；失败返回 null（不支持 tools 的 400 特判给降级信号） */
    private JsonNode callUpstream(LlmConfigEntity c, List<Object> messages, boolean withTools) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", c.model);
            body.put("messages", messages);
            if (withTools) {
                body.put("tools", tools());
                body.put("tool_choice", "auto");
            } else {
                body.put("max_tokens", c.maxTokens == null ? 2048 : c.maxTokens);
            }
            HttpRequest req = HttpRequest.newBuilder(URI.create(
                            c.baseUrl.replaceAll("/$", "") + "/chat/completions"))
                    .timeout(Duration.ofSeconds((c.timeoutSeconds == null ? 60 : c.timeoutSeconds) + 60L))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + (c.apiKey == null ? "" : c.apiKey))
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                log.warn("AI 会诊上游返回 {}: {}", resp.statusCode(), cap(resp.body(), 300));
                return null;
            }
            return json.readTree(resp.body()).path("choices").path(0).path("message");
        } catch (Exception e) {
            log.warn("AI 会诊上游调用失败: {}", e.toString());
            return null;
        }
    }

    private static String arg(String argsJson, String key, String def) {
        try {
            ObjectMapper m = new ObjectMapper();
            JsonNode n = m.readTree(argsJson == null || argsJson.isBlank() ? "{}" : argsJson).path(key);
            return n.isMissingNode() || n.isNull() ? def : n.asText();
        } catch (Exception e) {
            return def;
        }
    }

    private static String cap(String s, int n) {
        if (s == null) {
            return "";
        }
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }

}
