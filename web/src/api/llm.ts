import { http } from "@/api/http";

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
}

export interface LlmChatVO {
  content: string;
  reasoning: string;
  promptTokens: number;
  completionTokens: number;
  costMs: number;
  model: string;
}

export function getLlmConfig() {
  return http.get("/llm/config") as unknown as Promise<LlmConfigVO>;
}

/** null 字段保留服务端原值；apiKey 空串=保留原 key */
export function updateLlmConfig(payload: LlmConfigPayload) {
  return http.put("/llm/config", payload) as unknown as Promise<LlmConfigVO>;
}

export function testLlm(question?: string) {
  return http.post("/llm/test", { question }, { timeout: 300000 }) as unknown as Promise<LlmChatVO>;
}

export interface LlmTurn {
  role: "user" | "assistant";
  content: string;
}

export function llmChat(body: { question: string; history: LlmTurn[]; context?: string; instanceId?: string }) {
  return http.post("/llm/chat", body, { timeout: 300000 }) as unknown as Promise<LlmChatVO>;
}
