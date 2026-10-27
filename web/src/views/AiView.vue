<template>
  <el-row :gutter="12">
    <el-col :span="10">
      <el-card shadow="never" header="模型接入（OpenAI Chat Completions 兼容）">
        <el-alert v-if="!isAdmin" type="info" :closable="false" show-icon style="margin-bottom: 12px"
                  title="当前为只读视图：仅管理员可修改配置与测试连通" />
        <el-form label-position="top" size="small">
          <el-form-item label="Base URL（不含 /chat/completions 尾巴）">
            <el-input v-model="cfg.baseUrl" :disabled="!isAdmin" placeholder="https://api.openai.com/v1 或内网网关地址" />
          </el-form-item>
          <el-form-item label="API Key">
            <el-input v-model="cfg.apiKeyDraft" :disabled="!isAdmin" type="password" show-password
                      :placeholder="cfg.hasApiKey ? `已保存（${cfg.apiKeyMasked}），留空则保留` : 'sk-...'" />
          </el-form-item>
          <el-form-item label="模型名">
            <el-input v-model="cfg.model" :disabled="!isAdmin" placeholder="gpt-4o / o4-mini / deepseek-chat / glm-4.7 …" />
          </el-form-item>
          <el-form-item label="思考强度（reasoning_effort，推理模型专用）">
            <el-select v-model="cfg.reasoningEffort" :disabled="!isAdmin" style="width: 100%">
              <el-option v-for="o in efforts" :key="o.v" :value="o.v" :label="o.l" />
            </el-select>
            <div class="hint" v-if="cfg.reasoningEffort !== 'OFF'">
              推理模式：自动省略 temperature / top_p，max_tokens 以 max_completion_tokens 承载
            </div>
          </el-form-item>
          <el-form-item label="温度 temperature（0–2）">
            <el-slider v-model="cfg.temperature" :min="0" :max="2" :step="0.1" :disabled="!isAdmin || cfg.reasoningEffort !== 'OFF'" />
          </el-form-item>
          <el-form-item label="top_p（0–1）">
            <el-slider v-model="cfg.topP" :min="0" :max="1" :step="0.05" :disabled="!isAdmin || cfg.reasoningEffort !== 'OFF'" />
          </el-form-item>
          <el-row :gutter="10">
            <el-col :span="12">
              <el-form-item label="max_tokens">
                <el-input-number v-model="cfg.maxTokens" :min="128" :max="200000" :step="128" :disabled="!isAdmin" style="width:100%" />
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="超时（秒）">
                <el-input-number v-model="cfg.timeoutSeconds" :min="3" :max="600" :disabled="!isAdmin" style="width:100%" />
              </el-form-item>
            </el-col>
          </el-row>
          <el-form-item label="系统提示词（人设与输出规范）">
            <el-input v-model="cfg.systemPrompt" type="textarea" :rows="7" :disabled="!isAdmin" />
            <div class="row" style="margin-top:6px">
              <el-button size="small" text type="primary" :disabled="!isAdmin" @click="cfg.systemPrompt = DEFAULT_PROMPT">
                恢复默认提示词
              </el-button>
              <span class="hint">{{ (cfg.systemPrompt || "").length }} 字</span>
            </div>
          </el-form-item>
          <div class="row" v-if="isAdmin">
            <el-button type="primary" size="small" :loading="saving" @click="save">保存配置</el-button>
            <el-button size="small" :loading="testing" @click="runTest">测试连通</el-button>
          </div>
          <el-alert v-if="testOut" type="success" :closable="false" style="margin-top:10px">
            <div class="hint">测试成功 · {{ testOut.model }} · {{ testOut.costMs }}ms ·
              tokens {{ testOut.promptTokens }}/{{ testOut.completionTokens }}</div>
            <div style="margin-top:4px">{{ testOut.content }}</div>
          </el-alert>
        </el-form>
      </el-card>
    </el-col>

    <el-col :span="14">
      <el-card shadow="never" header="AI 问诊 · Diag Copilot（对话式排障）" class="chat-card">
        <div class="chat" ref="chatBox">
          <div v-for="(m, i) in msgs" :key="i" class="msg" :class="m.role === 'user' ? 'u' : 'a'">
            <template v-if="m.role === 'user'">{{ m.content }}</template>
            <template v-else>
              <el-collapse v-if="m.reasoning" class="think">
                <el-collapse-item :title="'思考过程（reasoning）'" :name="i">
                  <div class="reason">{{ m.reasoning }}</div>
                </el-collapse-item>
              </el-collapse>
              <div class="content">{{ m.content }}</div>
              <div class="meta" v-if="m.model">{{ m.model }} · {{ m.costMs }}ms · tokens {{ m.promptTokens }}/{{ m.completionTokens }}</div>
            </template>
          </div>
          <div v-if="pending" class="msg a pending">
            思考中… {{ elapsed }}s
            <div class="hint" style="margin-top:4px">推理模型在复杂分析上可能需要 30s 以上</div>
          </div>
          <el-empty v-if="!msgs.length && !pending" :image-size="60"
                    description="向诊断助手提问：粘贴线程栈 / 内存水位 / 告警信息，或直接描述现象" />
        </div>

        <el-collapse style="margin-top:8px">
          <el-collapse-item title="附加诊断上下文（可选：粘贴 thread / memory / jvm 输出）" name="ctx">
            <el-input v-model="context" type="textarea" :rows="5" placeholder="把诊断台的真实命令输出粘贴到这里，助手将结合它分析" />
          </el-collapse-item>
        </el-collapse>
        <div class="quick">
          <el-tag v-for="q in quicks" :key="q" size="small" effect="plain" class="q" @click="ask(q)">{{ q }}</el-tag>
        </div>
        <div class="composer">
          <el-input v-model="draft" type="textarea" :rows="3" placeholder="描述现象或提问（Enter 发送，Shift+Enter 换行）"
                    @keydown.enter.exact.prevent="ask()" />
          <el-button type="primary" :loading="pending" :disabled="!draft.trim()" @click="ask()">发送</el-button>
        </div>
      </el-card>
    </el-col>
  </el-row>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from "vue";
import { ElMessage } from "element-plus";
import { errMsg } from "@/api/http";
import { getLlmConfig, llmChat, testLlm, updateLlmConfig, type LlmChatVO, type LlmConfigVO, type LlmTurn } from "@/api/llm";
import { useAuthStore } from "@/stores/auth";

const auth = useAuthStore();
const isAdmin = computed(() => auth.can("admin:user"));

interface Msg extends LlmTurn { reasoning?: string; model?: string; costMs?: number; promptTokens?: number; completionTokens?: number }

const DEFAULT_PROMPT = `你是扁鹊（BianQue）平台的线上 JVM 诊断助手，面向运维与研发。回答要求：
1) 先给结论，再列证据（引用提供的线程栈/内存/JVM 数据），最后给处置建议；
2) 建议执行的 Arthas 命令仅限只读白名单（thread/memory/jvm/dashboard/tt/watch 只读用法），绝不建议 ognl/redefine/stop 等高危命令；
3) 信息不足时明确说明还缺哪类数据，不要臆测；
4) 使用简体中文，简洁分点。`;

const efforts = [
  { v: "OFF", l: "关闭（普通对话模型）" },
  { v: "MINIMAL", l: "minimal · 最快" },
  { v: "LOW", l: "low · 低" },
  { v: "MEDIUM", l: "medium · 中" },
  { v: "HIGH", l: "high · 深度思考" },
];

const cfg = ref<LlmConfigVO & { apiKeyDraft: string }>({
  baseUrl: "", hasApiKey: false, apiKeyMasked: "", model: "",
  temperature: 0.7, topP: 1, maxTokens: 2048, reasoningEffort: "OFF",
  timeoutSeconds: 60, systemPrompt: DEFAULT_PROMPT, updatedAt: "", configured: false,
  apiKeyDraft: "",
});
const saving = ref(false);
const testing = ref(false);
const testOut = ref<LlmChatVO | null>(null);

const msgs = ref<Msg[]>([]);
const draft = ref("");
const context = ref("");
const pending = ref(false);
const elapsed = ref(0);
let timer: number | undefined;

const quicks = [
  "分析这段线程栈：CPU 高的线程都在做什么？",
  "old gen 水位偏高，帮我判断是否泄漏",
  "给出排查慢接口的只读命令清单",
];

async function load() {
  try {
    const c = await getLlmConfig();
    cfg.value = { ...c, apiKeyDraft: "" };
    if (!c.systemPrompt) cfg.value.systemPrompt = DEFAULT_PROMPT;
  } catch (e) {
    ElMessage.error(errMsg(e));
  }
}

async function save() {
  saving.value = true;
  try {
    const c = await updateLlmConfig({
      baseUrl: cfg.value.baseUrl,
      apiKey: cfg.value.apiKeyDraft, // 空串=保留原 key
      model: cfg.value.model,
      temperature: cfg.value.temperature,
      topP: cfg.value.topP,
      maxTokens: cfg.value.maxTokens,
      reasoningEffort: cfg.value.reasoningEffort,
      timeoutSeconds: cfg.value.timeoutSeconds,
      systemPrompt: cfg.value.systemPrompt,
    });
    cfg.value = { ...c, apiKeyDraft: "" };
    ElMessage.success("配置已保存");
  } catch (e) {
    ElMessage.error(errMsg(e));
  } finally {
    saving.value = false;
  }
}

async function runTest() {
  testing.value = true;
  testOut.value = null;
  try {
    testOut.value = await testLlm();
  } catch (e) {
    ElMessage.error(errMsg(e));
  } finally {
    testing.value = false;
  }
}

function ask(preset?: string) {
  const q = (preset ?? draft.value).trim();
  if (!q || pending.value) return;
  if (preset) draft.value = "";
  else draft.value = "";
  const history: LlmTurn[] = msgs.value.slice(-10).map(m => ({ role: m.role, content: m.content }));
  msgs.value.push({ role: "user", content: q });
  pending.value = true;
  elapsed.value = 0;
  timer = window.setInterval(() => (elapsed.value += 1), 1000);
  scrollBottom();
  llmChat({ question: q, history, context: context.value.trim() || undefined })
    .then(out => {
      msgs.value.push({ role: "assistant", content: out.content, reasoning: out.reasoning || undefined,
        model: out.model, costMs: out.costMs, promptTokens: out.promptTokens, completionTokens: out.completionTokens });
    })
    .catch(e => {
      msgs.value.push({ role: "assistant", content: "调用失败：" + errMsg(e) });
    })
    .finally(() => {
      pending.value = false;
      window.clearInterval(timer);
      context.value = "";
      scrollBottom();
    });
}

function scrollBottom() {
  nextTick(() => {
    const box = chatBox.value;
    if (box) box.scrollTop = box.scrollHeight;
  });
}

const chatBox = ref<HTMLElement | undefined>();

onMounted(load);
</script>

<style scoped>
.hint { font-size: 11.5px; color: var(--el-text-color-secondary); }
.row { display: flex; align-items: center; gap: 10px; }
.chat-card :deep(.el-card__body) { display: flex; flex-direction: column; height: calc(100vh - 170px); }
.chat {
  flex: 1;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 4px 2px;
  min-height: 120px;
}
.msg { max-width: 88%; border-radius: 10px; padding: 9px 12px; font-size: 13px; line-height: 1.6; }
.msg.u { align-self: flex-end; background: var(--el-color-primary); color: #fff; border-bottom-right-radius: 3px; white-space: pre-wrap; }
.msg.a { align-self: flex-start; background: #f8fafc; border: 1px solid #eef1f6; border-bottom-left-radius: 3px; white-space: pre-wrap; }
.msg.pending { color: var(--el-text-color-secondary); }
.think { margin: -4px 0 6px; --el-collapse-header-height: 30px; }
.think :deep(.el-collapse-item__header) { font-size: 12px; color: var(--el-text-color-secondary); }
.reason { font-size: 12px; color: var(--el-text-color-secondary); white-space: pre-wrap; }
.meta { margin-top: 6px; font-size: 11px; color: var(--el-text-color-secondary); }
.quick { display: flex; gap: 8px; margin-top: 8px; flex-wrap: wrap; }
.q { cursor: pointer; }
.q:hover { color: var(--el-color-primary); border-color: var(--el-color-primary-light-5); }
.composer { display: flex; gap: 10px; margin-top: 8px; align-items: flex-end; }
.composer .el-button { height: 54px; }
</style>
