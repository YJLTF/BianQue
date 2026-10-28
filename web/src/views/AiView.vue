<template>
  <el-row :gutter="12">
    <el-col :span="configOpen ? 10 : 24">
      <el-card shadow="never" class="cfg-card" :class="{ folded: !configOpen }">
        <template #header>
          <div class="cfg-head" @click="toggleCfg" title="点击展开 / 折叠">
            <span class="cfg-title">模型接入</span>
            <span class="cfg-sum">
              <el-tag size="small" :type="cfg.configured ? 'success' : 'warning'" effect="plain">
                {{ cfg.configured ? "已配置" : "未配置" }}
              </el-tag>
              <span class="sum-text" :title="cfgSummary">{{ cfgSummary }}</span>
            </span>
            <svg class="cfg-arrow" :class="{ open: configOpen }" viewBox="0 0 24 24" width="14" height="14" fill="none"
                 stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round">
              <path d="m6 9 6 6 6-6" />
            </svg>
          </div>
        </template>
        <el-alert v-if="!isAdmin" type="info" :closable="false" show-icon style="margin-bottom: 12px"
                  title="当前为只读视图：仅管理员可修改配置与测试连通" />
        <el-form label-position="top" size="small">
          <el-form-item label="服务地址 Base URL">
            <el-input v-model="cfg.baseUrl" :disabled="!isAdmin" placeholder="https://api.openai.com/v1 或内网网关地址" />
          </el-form-item>
          <el-form-item label="API Key">
            <el-input v-model="cfg.apiKeyDraft" :disabled="!isAdmin" type="password" show-password
                      :placeholder="cfg.hasApiKey ? `已保存（${cfg.apiKeyMasked}），留空则保留` : 'sk-...'" />
          </el-form-item>
          <el-form-item label="模型名">
            <el-input v-model="cfg.model" :disabled="!isAdmin" placeholder="gpt-4o / o4-mini / deepseek-chat / glm-4.7 …" />
          </el-form-item>
          <el-form-item label="默认思考强度（推理模型生效）">
            <el-select v-model="cfg.reasoningEffort" :disabled="!isAdmin" style="width: 100%">
              <el-option v-for="o in efforts" :key="o.v" :value="o.v" :label="o.l" />
            </el-select>
            <div class="hint" v-if="cfg.reasoningEffort !== 'OFF'">
              每次提问可在输入框下方临时切换（ZCode 式思考强度）；推理模式下自动省略 temperature / top_p
            </div>
          </el-form-item>
          <el-form-item>
            <template #label>
              <span>推理参数映射（可选 · 按供应商自定义）</span>
              <el-tooltip placement="top" effect="light">
                <template #content>
                  按思考强度自定义请求体附加参数（JSON 深度合并），适配非 OpenAI 风格的推理开关。<br />
                  键：off / minimal / low / medium / high；某等级未给出时回退内置映射。
                </template>
                <svg viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor"
                     stroke-width="2" stroke-linecap="round" style="vertical-align: -1px; margin-left: 4px">
                  <circle cx="12" cy="12" r="10" /><path d="M12 16v-4M12 8h.01" />
                </svg>
              </el-tooltip>
            </template>
            <el-input v-model="cfg.reasoningParams" type="textarea" :rows="6" :disabled="!isAdmin"
                      :placeholder="MAPPING_EXAMPLE" />
            <div class="row" style="margin-top:6px">
              <el-button size="small" text type="primary" :disabled="!isAdmin" @click="cfg.reasoningParams = ''">
                清空（用内置映射）
              </el-button>
              <span class="hint">内置：非 off → reasoning_effort + max_completion_tokens；off → temperature/top_p/max_tokens</span>
            </div>
          </el-form-item>
          <el-row :gutter="10">
            <el-col :span="12">
              <el-form-item label="温度 temperature">
                <el-slider v-model="cfg.temperature" :min="0" :max="2" :step="0.1"
                           :disabled="!isAdmin || cfg.reasoningEffort !== 'OFF'" />
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="top_p">
                <el-slider v-model="cfg.topP" :min="0" :max="1" :step="0.05"
                           :disabled="!isAdmin || cfg.reasoningEffort !== 'OFF'" />
              </el-form-item>
            </el-col>
          </el-row>
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
          <el-form-item label="系统提示词">
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
            <div v-if="testOut.reasoning" class="reason" style="margin-top:4px">{{ testOut.reasoning }}</div>
            <div style="margin-top:4px">{{ testOut.content }}</div>
          </el-alert>
        </el-form>
      </el-card>
    </el-col>

    <el-col :span="configOpen ? 14 : 24">
      <el-card shadow="never" header="AI 问诊 · 对话式排障" class="chat-card"
               :class="{ 'full-mode': !configOpen }">
        <div class="chat" ref="chatBox">
          <div v-for="(m, i) in msgs" :key="i" class="msg" :class="m.role === 'user' ? 'u' : 'a'">
            <template v-if="m.role === 'user'">{{ m.content }}</template>
            <template v-else>
              <div v-if="m.reasoning || m.streaming" class="think" :class="{ open: m.showThink }">
                <div class="think-hd" @click="m.showThink = !m.showThink">
                  <svg class="chev" viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor"
                       stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round">
                    <path d="m6 9 6 6 6-6" />
                  </svg>
                  <span>思考过程</span>
                  <span v-if="m.streaming && !m.content" class="think-live">思考中… {{ elapsed }}s</span>
                </div>
                <div v-show="m.showThink" class="reason">{{ m.reasoning }}</div>
              </div>
              <div class="content" v-show="m.content || !m.streaming">{{ m.content }}<span v-if="m.streaming && m.content" class="cursor">▍</span></div>
              <div class="refs" v-if="m.refs?.length">
                <span class="hint">参考文档：</span>
                <el-tag v-for="r in m.refs" :key="r.slug" size="small" effect="plain" class="ref"
                        :title="r.path" @click="goDoc(r.path)">{{ r.title }}</el-tag>
              </div>
              <div class="meta" v-if="m.model && !m.streaming">
                {{ m.model }} · {{ m.costMs }}ms · tokens {{ m.promptTokens }}/{{ m.completionTokens }}
              </div>
            </template>
          </div>
          <el-empty v-if="!msgs.length" :image-size="60"
                    description="粘贴线程栈 / 内存水位 / 告警信息，或直接描述现象" />
        </div>

        <el-collapse style="margin-top:8px">
          <el-collapse-item title="附加诊断上下文（可粘贴诊断命令输出）" name="ctx">
            <el-input v-model="context" type="textarea" :rows="5" placeholder="把诊断台的真实命令输出粘贴到这里，助手将结合它分析" />
          </el-collapse-item>
        </el-collapse>
        <div class="quick">
          <el-tag v-for="q in quicks" :key="q" size="small" effect="plain" class="q" @click="ask(q)">{{ q }}</el-tag>
        </div>
        <div class="composer">
          <div class="composer-main">
            <el-input v-model="draft" type="textarea" :rows="3" placeholder="描述现象或提问（Enter 发送，Shift+Enter 换行）"
                      @keydown.enter.exact.prevent="ask()" />
            <el-button type="primary" :loading="pending" :disabled="!draft.trim()" @click="ask()">发送</el-button>
          </div>
          <div class="composer-sub">
            <span class="lbl">思考强度</span>
            <button v-for="o in chatEfforts" :key="o.v" type="button" class="pill"
                    :class="{ on: effort === o.v }" :title="o.tip" @click="setEffort(o.v)">{{ o.l }}</button>
            <span class="hint" style="margin-left: auto">复杂问题选「高」；思考与回答均实时流式输出</span>
          </div>
        </div>
      </el-card>
    </el-col>
  </el-row>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref } from "vue";
import { useRouter } from "vue-router";
import { ElMessage } from "element-plus";
import { errMsg } from "@/api/http";
import {
  getLlmConfig, llmChatStream, testLlm, updateLlmConfig,
  type LlmChatVO, type LlmConfigVO, type LlmDocRef, type LlmTurn,
} from "@/api/llm";
import { useAuthStore } from "@/stores/auth";

const auth = useAuthStore();
const router = useRouter();
const isAdmin = computed(() => auth.can("admin:user"));

interface Msg extends LlmTurn {
  reasoning?: string;
  model?: string;
  costMs?: number;
  promptTokens?: number;
  completionTokens?: number;
  refs?: LlmDocRef[];
  /** SSE 流式进行中：渲染光标与「思考中」态 */
  streaming?: boolean;
  /** 思考过程面板展开态（流式期间自动展开，正文开始后自动收起） */
  showThink?: boolean;
}

const DEFAULT_PROMPT = `你是扁鹊（BianQue）平台的线上 JVM 诊断助手，面向运维与研发。回答要求：
1) 先给结论，再列证据（引用提供的线程栈/内存/JVM 数据），最后给处置建议；
2) 建议执行的 Arthas 命令仅限只读白名单（thread/memory/jvm/dashboard/tt/watch 只读用法），绝不建议 ognl/redefine/stop 等高危命令；
3) 信息不足时明确说明还缺哪类数据，不要臆测；
4) 使用简体中文，简洁分点。`;

const MAPPING_EXAMPLE = `留空 = 内置 OpenAI 风格。自定义示例：
{
  "off":  { "thinking": { "type": "disabled" } },
  "low":  { "reasoning_effort": "low" },
  "high": { "reasoning_effort": "high", "top_p": 0.95 }
}
键 off/minimal/low/medium/high → 该思考强度下深度合并进请求体`;

const efforts = [
  { v: "OFF", l: "关闭（普通对话）" },
  { v: "MINIMAL", l: "minimal（最快）" },
  { v: "LOW", l: "low（较低）" },
  { v: "MEDIUM", l: "medium（中等）" },
  { v: "HIGH", l: "high（深度思考）" },
];

/** ZCode 式会话级思考强度：默认跟随接入配置，也可按条覆盖 */
type EffortKey = "default" | "off" | "low" | "medium" | "high";
const EFFORT_KEY = "bianque.aiEffort";
const chatEfforts = [
  { v: "default", l: "默认", tip: "跟随「模型接入」里配置的默认思考强度" },
  { v: "off", l: "关", tip: "本条不思考（普通对话模式）" },
  { v: "low", l: "低", tip: "低强度思考" },
  { v: "medium", l: "中", tip: "中等强度思考" },
  { v: "high", l: "高", tip: "深度思考（更慢更细）" },
] as const;
const effort = ref<EffortKey>((localStorage.getItem(EFFORT_KEY) as EffortKey) || "default");

function setEffort(v: EffortKey) {
  effort.value = v;
  localStorage.setItem(EFFORT_KEY, v);
}

function levelParam() {
  return effort.value === "default" ? undefined : effort.value;
}

const cfg = ref<LlmConfigVO & { apiKeyDraft: string }>({
  baseUrl: "", hasApiKey: false, apiKeyMasked: "", model: "",
  temperature: 0.7, topP: 1, maxTokens: 2048, reasoningEffort: "OFF",
  timeoutSeconds: 60, systemPrompt: DEFAULT_PROMPT, reasoningParams: "",
  updatedAt: "", configured: false, apiKeyDraft: "",
});
const saving = ref(false);
const testing = ref(false);
const testOut = ref<LlmChatVO | null>(null);

const CFG_OPEN_KEY = "bianque.aiCfgOpen";
const savedOpen = localStorage.getItem(CFG_OPEN_KEY);
const configOpen = ref(savedOpen === null ? true : savedOpen === "1");

const effortShort = computed(() => {
  const e = cfg.value.reasoningEffort;
  return e === "OFF" ? "普通模式" : `思考=${e.toLowerCase()}`;
});
const cfgSummary = computed(() => `${cfg.value.model || "未设模型"} · ${effortShort.value}`);

function toggleCfg() {
  configOpen.value = !configOpen.value;
  localStorage.setItem(CFG_OPEN_KEY, configOpen.value ? "1" : "0");
}

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
    cfg.value = { ...c, apiKeyDraft: "", reasoningParams: c.reasoningParams || "" };
    if (!c.systemPrompt) cfg.value.systemPrompt = DEFAULT_PROMPT;
    // 首次访问（无本地记忆）时：尚未配置默认展开引导设置，已配置默认折叠
    if (savedOpen === null) configOpen.value = !c.configured;
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
      reasoningParams: cfg.value.reasoningParams, // 空串=清空回内置映射
    });
    cfg.value = { ...c, apiKeyDraft: "", reasoningParams: c.reasoningParams || "" };
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
    testOut.value = await testLlm(undefined, levelParam());
  } catch (e) {
    ElMessage.error(errMsg(e));
  } finally {
    testing.value = false;
  }
}

function ask(preset?: string) {
  const q = (preset ?? draft.value).trim();
  if (!q || pending.value) return;
  draft.value = "";
  const history: LlmTurn[] = msgs.value.slice(-10).map(m => ({ role: m.role, content: m.content }));
  msgs.value.push({ role: "user", content: q });
  const m = reactive<Msg>({ role: "assistant", content: "", streaming: true, showThink: true });
  msgs.value.push(m);
  pending.value = true;
  elapsed.value = 0;
  timer = window.setInterval(() => (elapsed.value += 1), 1000);
  scrollBottom();
  llmChatStream(
    { question: q, history, context: context.value.trim() || undefined, reasoningLevel: levelParam() },
    {
      onReasoning: t => {
        m.reasoning = (m.reasoning || "") + t;
        scrollBottom();
      },
      onContent: t => {
        if (m.showThink) m.showThink = false; // 正文开始后自动收起思考面板
        m.content += t;
        scrollBottom();
      },
      onDone: (out: LlmChatVO) => {
        m.content = out.content || m.content;
        m.reasoning = out.reasoning || m.reasoning;
        m.model = out.model;
        m.costMs = out.costMs;
        m.promptTokens = out.promptTokens;
        m.completionTokens = out.completionTokens;
        m.refs = out.refs?.length ? out.refs : undefined;
      },
      onError: msg => {
        m.content = (m.content ? m.content + "\n\n" : "") + "调用失败：" + msg;
      },
    },
  )
    .catch(e => {
      m.content = (m.content ? m.content + "\n\n" : "") + "调用失败：" + errMsg(e);
    })
    .finally(() => {
      m.streaming = false;
      pending.value = false;
      window.clearInterval(timer);
      context.value = "";
      scrollBottom();
    });
}

function goDoc(path: string) {
  router.push({ path: "/docs", query: { page: path } });
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
.cfg-head { display: flex; align-items: center; gap: 10px; cursor: pointer; user-select: none; min-width: 0; }
.cfg-head:hover .cfg-title { color: var(--el-color-primary); }
.cfg-title { font-weight: 600; white-space: nowrap; }
.cfg-sum { display: flex; align-items: center; gap: 8px; margin-left: auto; min-width: 0; }
.sum-text { font-size: 12px; color: var(--el-text-color-secondary); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.cfg-arrow { flex: none; color: var(--el-text-color-secondary); transition: transform 0.2s; }
.cfg-arrow.open { transform: rotate(180deg); }
.cfg-card.folded :deep(.el-card__body) { display: none; }
.chat-card :deep(.el-card__body) { display: flex; flex-direction: column; height: calc(100vh - 170px); }
.chat-card.full-mode :deep(.el-card__body) { height: calc(100vh - 234px); }
.chat {
  flex: 1;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 4px 2px;
  min-height: 120px;
}
.msg { max-width: min(88%, 780px); border-radius: 10px; padding: 9px 12px; font-size: 13px; line-height: 1.6; }
.msg.u { align-self: flex-end; background: var(--el-color-primary); color: #fff; border-bottom-right-radius: 3px; white-space: pre-wrap; }
.msg.a { align-self: flex-start; background: #f8fafc; border: 1px solid #eef1f6; border-bottom-left-radius: 3px; white-space: pre-wrap; }
.think { margin: -4px 0 6px; border: 1px dashed #e2e8f2; border-radius: 8px; background: #fbfcfe; }
.think-hd { display: flex; align-items: center; gap: 6px; padding: 5px 10px; font-size: 12px; color: var(--el-text-color-secondary); cursor: pointer; user-select: none; }
.think-hd:hover { color: var(--el-color-primary); }
.think.open .chev { transform: rotate(180deg); }
.chev { flex: none; transition: transform 0.2s; }
.think-live { color: var(--el-color-primary); }
.reason { padding: 4px 12px 9px; font-size: 12px; color: var(--el-text-color-secondary); white-space: pre-wrap; max-height: 260px; overflow-y: auto; }
.cursor { display: inline-block; color: var(--el-color-primary); animation: blink 1s step-start infinite; }
@keyframes blink { 50% { opacity: 0; } }
.meta { margin-top: 6px; font-size: 11px; color: var(--el-text-color-secondary); }
.refs { margin-top: 7px; display: flex; align-items: center; gap: 6px; flex-wrap: wrap; white-space: normal; }
.ref { cursor: pointer; }
.ref:hover { color: var(--el-color-primary); border-color: var(--el-color-primary-light-5); }
.quick { display: flex; gap: 8px; margin-top: 8px; flex-wrap: wrap; }
.q { cursor: pointer; }
.q:hover { color: var(--el-color-primary); border-color: var(--el-color-primary-light-5); }
.composer { margin-top: 8px; }
.composer-main { display: flex; gap: 10px; align-items: flex-end; }
.composer-main .el-button { height: 54px; }
.composer-sub { display: flex; align-items: center; gap: 6px; margin-top: 8px; }
.lbl { font-size: 12px; color: var(--el-text-color-secondary); }
.pill {
  height: 22px;
  padding: 0 10px;
  font-size: 12px;
  color: var(--el-text-color-regular);
  background: #fff;
  border: 1px solid #e2e8f2;
  border-radius: 999px;
  cursor: pointer;
}
.pill:hover { color: var(--el-color-primary); border-color: var(--el-color-primary-light-5); }
.pill.on {
  color: var(--el-color-primary);
  border-color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
  font-weight: 600;
}
</style>
