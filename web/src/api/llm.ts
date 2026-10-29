import { http } from "@/api/http";
import { useAuthStore } from "@/stores/auth";

/** LLM 接入配置（OpenAI Chat Completions 兼容）；api-key 只回传掩码 */
export interface LlmConfigVO {
  baseUrl: string;
  hasApiKey: boolean;
  apiKeyMasked: string;
  model: string;
  temperature: number;
  topP: number;
  maxTokens: number;
  /** OFF=不发送 reasoning_effort；MINIMAL/LOW/MEDIUM/HIGH 透传 */
  reasoningEffort: "OFF" | "MINIMAL" | "LOW" | "MEDIUM" | "HIGH";
  timeoutSeconds: number;
  systemPrompt: string;
  /** 推理参数映射 JSON（键 off/minimal/low/medium/high → 请求体附加参数）；空=内置映射 */
  reasoningParams: string;
  /** 文档向量化（RAG）：留空 = 关键词检索兜底 */
  embedBaseUrl: string;
  hasEmbedApiKey: boolean;
  embedApiKeyMasked: string;
  embedModel: string;
  embedDims: number | null;
  updatedAt: string;
  configured: boolean;
}

export interface LlmConfigPayload {
  baseUrl?: string;
  apiKey?: string;
  model?: string;
  temperature?: number;
  topP?: number;
  maxTokens?: number;
  reasoningEffort?: string;
  timeoutSeconds?: number;
  systemPrompt?: string;
  reasoningParams?: string;
  embedBaseUrl?: string;
  embedApiKey?: string;
  embedModel?: string;
  embedDims?: number;
}

/** 命中的知识库参考文档（点击跳文档中心） */
export interface LlmDocRef {
  slug: string;
  title: string;
  path: string;
}

export interface LlmChatVO {
  content: string;
  reasoning: string;
  promptTokens: number;
  completionTokens: number;
  costMs: number;
  model: string;
  /** 本次回答自动检索命中的文档中心页面 */
  refs: LlmDocRef[];
}

export function getLlmConfig() {
  return http.get("/llm/config") as unknown as Promise<LlmConfigVO>;
}

/** null 字段保留服务端原值；apiKey 空串=保留原 key；reasoningParams 空串=清空用内置映射 */
export function updateLlmConfig(payload: LlmConfigPayload) {
  return http.put("/llm/config", payload) as unknown as Promise<LlmConfigVO>;
}

export function testLlm(question?: string, reasoningLevel?: string) {
  return http.post("/llm/test", { question, reasoningLevel }, { timeout: 300000 }) as unknown as Promise<LlmChatVO>;
}

export interface LlmTurn {
  role: "user" | "assistant";
  content: string;
}

export function llmChat(body: {
  question: string;
  history: LlmTurn[];
  context?: string;
  instanceId?: string;
  reasoningLevel?: string;
}) {
  return http.post("/llm/chat", body, { timeout: 300000 }) as unknown as Promise<LlmChatVO>;
}

/** 向量索引状态（已配置 / 块数 / 模型） */
export function embeddingStatus() {
  return http.get("/llm/embeddings/status") as unknown as Promise<{
    configured: boolean; chunks: number; indexing?: boolean; model?: string; dims?: number;
  }>;
}

/** 重建文档向量索引（仅 admin，同步执行） */
export function reindexEmbeddings() {
  return http.post("/llm/embeddings/reindex", {}, { timeout: 300000 }) as unknown as Promise<{
    pages: number; indexed: number; unchangedChunks: number; model: string;
  }>;
}

export interface LlmStreamHandlers {
  onReasoning: (text: string) => void;
  onContent: (text: string) => void;
  onDone: (out: LlmChatVO) => void;
  onError: (message: string) => void;
}

/** AI 会诊工具步骤（/api/llm/consult/stream 的 tool 事件） */
export interface ConsultStepVO {
  step: number;
  tool: string;
  argsJson: string;
  command: string;
  decision: "auto" | "approved" | "denied" | "timeout" | "blocked";
  status: "ok" | "fail" | "denied" | "blocked";
  summary: string;
  costMs: number;
}

export interface ConsultHandlers {
  onStart: (consultId: string) => void;
  onTool: (step: ConsultStepVO) => void;
  onConfirmRequired: (step: number, tool: string, command: string) => void;
  onReasoning: (text: string) => void;
  onContent: (text: string) => void;
  onDone: (content: string, steps: ConsultStepVO[]) => void;
  onError: (message: string) => void;
}

/**
 * SSE 流式会诊：事件 start/tool/confirm_required/reasoning/content/done/error。
 * signal 支持中断；与 llmChatStream 同形的手工 SSE 解析。
 */
export async function consultStream(
  body: { instanceId: string; question: string; history: LlmTurn[] },
  h: ConsultHandlers,
  opts?: { signal?: AbortSignal },
) {
  const auth = useAuthStore();
  const resp = await fetch("/api/llm/consult/stream", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      ...(auth.user?.token ? { Authorization: `Bearer ${auth.user.token}` } : {}),
    },
    body: JSON.stringify(body),
    signal: opts?.signal,
  });
  if (!resp.ok || !resp.body) {
    let message = `HTTP ${resp.status}`;
    try {
      const j = await resp.json();
      message = j.message || j.error || message;
    } catch {
      // 非 JSON 错误体
    }
    throw new Error(message);
  }
  const reader = resp.body.getReader();
  const dec = new TextDecoder();
  let buf = "";

  const handle = (block: string) => {
    let event = "message";
    const data: string[] = [];
    for (const line of block.split("\n")) {
      if (line.startsWith("event:")) event = line.slice(6).trim();
      else if (line.startsWith("data:")) data.push(line.slice(5).trim());
    }
    if (!data.length) return;
    let payload: Record<string, unknown>;
    try {
      payload = JSON.parse(data.join("\n"));
    } catch {
      return;
    }
    switch (event) {
      case "start":
        h.onStart(String(payload.consultId ?? ""));
        break;
      case "tool":
        h.onTool(payload as unknown as ConsultStepVO);
        break;
      case "confirm_required":
        h.onConfirmRequired(Number(payload.step ?? 0), String(payload.tool ?? ""), String(payload.command ?? ""));
        break;
      case "reasoning":
        if (payload.text) h.onReasoning(String(payload.text));
        break;
      case "content":
        if (payload.text) h.onContent(String(payload.text));
        break;
      case "done":
        h.onDone(String(payload.content ?? ""), (payload.steps ?? []) as ConsultStepVO[]);
        break;
      case "error":
        throw new Error(String(payload.message || "会诊失败"));
      default:
        break;
    }
  };

  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    buf += dec.decode(value, { stream: true });
    let idx;
    while ((idx = buf.indexOf("\n\n")) >= 0) {
      const block = buf.slice(0, idx);
      buf = buf.slice(idx + 2);
      handle(block);
    }
  }
}

/** 会诊审批决策（增强命令审批卡） */
export function consultDecision(consultId: string, step: number, approved: boolean) {
  return http.post("/llm/consult/decision", { consultId, step, approved }) as unknown as Promise<{ ok: boolean }>;
}

/**
 * SSE 流式对话：fetch + ReadableStream 手工解析（axios 不支持浏览器侧流式响应）。
 * 事件：reasoning/content 增量 → done 全量汇总（含 refs）→ error 失败。
 */
export async function llmChatStream(
  body: { question: string; history: LlmTurn[]; context?: string; instanceId?: string; reasoningLevel?: string },
  h: LlmStreamHandlers,
  opts?: { signal?: AbortSignal },
) {
  const auth = useAuthStore();
  const resp = await fetch("/api/llm/chat/stream", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      ...(auth.user?.token ? { Authorization: `Bearer ${auth.user.token}` } : {}),
    },
    body: JSON.stringify(body),
    signal: opts?.signal,
  });
  if (resp.status === 404) {
    // 旧版服务端无流式端点 → 回退同步链路
    const out = await llmChat(body);
    if (out.reasoning) h.onReasoning(out.reasoning);
    h.onContent(out.content);
    h.onDone(out);
    return;
  }
  if (!resp.ok || !resp.body) {
    let message = `HTTP ${resp.status}`;
    try {
      const j = await resp.json();
      message = j.message || j.error || message;
    } catch {
      // 非 JSON 错误体
    }
    throw new Error(message);
  }
  const reader = resp.body.getReader();
  const dec = new TextDecoder();
  let buf = "";

  const handle = (block: string) => {
    let event = "message";
    const data: string[] = [];
    for (const line of block.split("\n")) {
      if (line.startsWith("event:")) event = line.slice(6).trim();
      else if (line.startsWith("data:")) data.push(line.slice(5).trim());
    }
    if (!data.length) return;
    let payload: Record<string, unknown>;
    try {
      payload = JSON.parse(data.join("\n"));
    } catch {
      return;
    }
    if (event === "reasoning" && payload.text) h.onReasoning(String(payload.text));
    else if (event === "content" && payload.text) h.onContent(String(payload.text));
    else if (event === "done") h.onDone(payload as unknown as LlmChatVO);
    else if (event === "error") throw new Error(String(payload.message || "流式调用失败"));
  };

  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    buf += dec.decode(value, { stream: true });
    let idx;
    while ((idx = buf.indexOf("\n\n")) >= 0) {
      const block = buf.slice(0, idx);
      buf = buf.slice(idx + 2);
      handle(block);
    }
  }
}
