<template>
  <el-row :gutter="12">
    <el-col :span="6">
      <el-card shadow="never" header="应用 / 实例">
        <el-select v-model="instanceId" filterable class="instance-select" popper-class="opt-popper"
                   style="width: 100%">
          <el-option v-for="i in instances" :key="i.instanceId" :value="i.instanceId"
                     :label="compactLabel(i)" :disabled="!i.online">
            <div class="opt">
              <div class="opt-t">{{ i.appId }}<span class="opt-id"> / {{ i.instanceId }}</span></div>
              <div class="opt-s">{{ channelName(i) }} · {{ i.host || "-" }} · pid {{ pidOf(i.instanceId) }}{{ i.online ? "" : " · 离线" }}</div>
            </div>
          </el-option>
        </el-select>
        <div class="sub ellip" style="margin-top: 8px" :title="fullLabel">
          {{ instanceId ? `当前：${fullLabel}` : "未选择实例" }}
        </div>
        <div class="sub">
          会话：{{ session.active ? `剩余 ${remain}（超时自动 reset+stop）` : "未开启" }}
        </div>
        <el-button size="small" :disabled="!session.active" style="margin-top: 8px" @click="closeSessionNow">
          结束并重置
        </el-button>
        <template v-if="localProcs.localEnabled">
          <div class="sec-hd" @click="localOpen = !localOpen">
            <svg class="chev" :class="{ open: localOpen }" viewBox="0 0 24 24" width="12" height="12" fill="none"
                 stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round">
              <path d="m6 9 6 6 6-6" />
            </svg>
            <span>本机进程纳管（{{ localProcs.processes.length }}）</span>
          </div>
          <div v-show="localOpen">
            <div v-for="p in localProcs.processes" :key="p.pid" class="lproc">
              <code class="cmd">{{ p.mainClass }}</code>
              <span class="sub">#{{ p.pid }}</span>
              <el-button v-if="!managedPids.has(p.pid)" size="small" type="primary" plain
                         :loading="attaching === p.pid" @click="attach(p.pid)">纳管</el-button>
              <el-tag v-else size="small" type="success" effect="plain">已纳管</el-tag>
            </div>
            <div class="sub" style="margin-top: 6px">纳管后可对其执行真实 Arthas 命令</div>
          </div>
        </template>
        <div v-else class="sub" style="margin-top: 10px">本机纳管未启用（local-enabled=false）</div>
      </el-card>
    </el-col>

    <el-col :span="18">
      <el-card shadow="never" header="场景模板">
        <el-space wrap>
          <el-tag v-for="t in tpls" :key="t.id" class="tpl" type="info" effect="plain"
                  style="cursor: pointer" @click="applyTemplate(t)">
            {{ t.name }}<span v-if="t.placeholders.length" class="tpl-ph">{{ t.placeholders.length }}参</span>
          </el-tag>
        </el-space>
        <div class="sub" style="margin-top: 6px">模板由模板中心统一管理（管理设置可增改）；带「N参」的模板点击后填参</div>
      </el-card>
      <el-card shadow="never" header="命令构建" style="margin-top: 12px">
        <el-input v-model="command" type="textarea" :rows="3" placeholder="watch 类名 方法名 '{params,returnObj}' -n 100" />
        <div style="margin-top: 8px" class="row">
          <el-tag :type="risk.type" effect="plain">{{ risk.label }}</el-tag>
          <el-button type="primary" size="small" :loading="running"
                     :disabled="!instanceId || !command.trim()" @click="onExecute">
            执行诊断
          </el-button>
          <el-button size="small" :disabled="!command.trim()" @click="saveAsTemplate">存为模板</el-button>
          <el-tag v-if="result && !blocked" size="small" :type="result.channel === 'real' ? 'success' : 'info'" effect="plain">
            {{ result.channel === "real" ? "真实执行" : "演示模拟" }} · {{ result.costMs }}ms
          </el-tag>
        </div>
        <div class="sub" style="margin-top: 8px">
          高危命令将被平台黑名单拦截并审计；watch / trace 等增强命令限次限频，会话结束自动 reset+stop 防增强残留
        </div>
      </el-card>
      <el-card shadow="never" style="margin-top: 12px">
        <template #header>
          <div class="card-hd">
            <span>{{ resultHeader }}</span>
            <span class="row">
              <el-button v-if="result && !blocked" size="small" text @click="exportReport">导出报告</el-button>
              <el-radio-group v-if="result?.metrics" v-model="viewMode" size="small">
                <el-radio-button value="chart">图表</el-radio-button>
                <el-radio-button value="text">文本</el-radio-button>
              </el-radio-group>
            </span>
          </div>
        </template>
        <div v-loading="running" element-loading-text="命令执行中，请稍候…" class="result-body">
          <el-alert v-if="blocked" type="error" :closable="false" :title="result?.error" show-icon />
          <DiagCharts v-else-if="result && viewMode === 'chart' && result.metrics" :metrics="result.metrics" />
          <pre v-else-if="result" class="out">{{ result.output }}</pre>
          <el-empty v-else description="选择实例与命令，执行后在此查看输出（thread/memory/jvm/dashboard/monitor 自动出图）" :image-size="60" />
        </div>
        <div v-if="result && !blocked" class="ai-row">
          <el-button size="small" type="primary" plain :loading="aiRunning"
                     :disabled="!result.output?.trim() || aiRunning" @click="runAi">AI 分析</el-button>
          <el-button v-if="aiRunning" size="small" @click="stopAi">停止</el-button>
          <el-button v-if="aiDone && !aiRunning" size="small" text type="primary" @click="goAsk">转问诊台追问</el-button>
          <span class="sub">结合本次输出就地生成分析（结论 / 根因方向 / 下一步建议），不消耗会话</span>
        </div>
        <div v-if="aiOpen" class="ai-panel">
          <div v-if="aiReasoning" class="think" :class="{ open: aiThinkOpen }">
            <div class="think-hd" @click="aiThinkOpen = !aiThinkOpen">
              <svg class="chev" viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor"
                   stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round">
                <path d="m6 9 6 6 6-6" />
              </svg>
              <span>思考过程</span>
            </div>
            <div v-show="aiThinkOpen" class="reason">{{ aiReasoning }}</div>
          </div>
          <MdView v-if="aiContent || !aiRunning" :content="aiContent" :streaming="aiRunning" />
          <div v-else class="sub">分析中…</div>
          <div v-if="aiRefs?.length" class="refs">
            <span class="sub">参考文档：</span>
            <el-tag v-for="r in aiRefs" :key="r.slug" size="small" effect="plain" class="ref"
                    :title="r.path" @click="goDoc(r.path)">{{ r.title }}</el-tag>
          </div>
        </div>
      </el-card>
    </el-col>

  </el-row>

  <el-card shadow="never" class="tools-card" style="margin-top: 12px">
    <el-tabs v-model="toolTab">
      <el-tab-pane label="命令速查" name="cheats">
        <el-table :data="cmdDocs" size="small" @row-click="goDoc">
          <el-table-column prop="slug" label="命令" width="100" />
          <el-table-column prop="risk" label="风险" width="140">
            <template #default="{ row }">
              <el-tag size="small" :type="riskMeta[row.risk]?.type ?? 'info'" effect="plain">
                {{ riskMeta[row.risk]?.label ?? row.risk }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="path" label="文档" min-width="240" />
        </el-table>
        <div class="sub" style="margin-top: 8px">点击行直达文档中心对应命令页；完整命令手册见「文档中心」</div>
      </el-tab-pane>
      <el-tab-pane label="性能剖析（火焰图）" name="profile">
        <div class="row">
          <el-button size="small" type="primary" plain :loading="profiling"
                     :disabled="!instanceId || profiling" @click="startProfile">开始 CPU 采样</el-button>
          <el-button size="small" type="warning" plain :loading="stopping"
                     :disabled="!profiling || stopping" @click="stopProfile">停止并出图</el-button>
          <span class="sub">profiler 经命令通道（本机/探针/拟真一致）；产物 ≤20MB，同源 iframe 渲染</span>
        </div>
        <el-alert v-if="profileTip" :title="profileTip" type="info" :closable="false" style="margin-top: 8px" />
        <div v-if="flameUrl" class="flame-box">
          <iframe :src="flameUrl" class="flame-frame" title="火焰图" />
        </div>
      </el-tab-pane>
      <el-tab-pane label="双实例对比（火山图）" name="compare">
        <div class="row" style="flex-wrap: wrap">
          <el-select v-model="cmpTarget" filterable size="small" placeholder="目标实例（如故障机）" style="width: 190px">
            <el-option v-for="i in instances.filter(i => i.online)" :key="i.instanceId" :value="i.instanceId"
                       :label="`${i.appId} / ${i.instanceId}`" />
          </el-select>
          <span class="sub">vs</span>
          <el-select v-model="cmpBase" filterable size="small" placeholder="基线实例（如正常机）" style="width: 190px">
            <el-option v-for="i in instances.filter(i => i.online)" :key="i.instanceId" :value="i.instanceId"
                       :label="`${i.appId} / ${i.instanceId}`" />
          </el-select>
          <el-select v-model="cmpRounds" size="small" style="width: 100px">
            <el-option :value="3" label="采样 3 轮" />
            <el-option :value="5" label="采样 5 轮" />
            <el-option :value="8" label="采样 8 轮" />
          </el-select>
          <el-button size="small" type="primary" :loading="cmpRunning"
                     :disabled="!cmpTarget || !cmpBase || cmpTarget === cmpBase" @click="runCompare">开始对比</el-button>
        </div>
        <el-alert v-if="cmpRunning" title="采样进行中：两实例各执行 memory/dashboard/jvm ×N 轮，请稍候…"
                  type="info" :closable="false" style="margin-top: 8px" />
        <template v-if="cmpResult">
          <el-alert v-if="cmpResult.errors?.length" type="warning" :closable="false" style="margin-top: 8px"
                    :title="`部分命令未取得有效样本（${cmpResult.errors.length} 条），不影响已取得指标的对比`"
                    :description="cmpResult.errors.join('；')" />
          <VolcanoChart v-if="cmpHasSufficient" :metrics="cmpResult.metrics" :rounds="cmpResult.rounds" />
          <el-table :data="cmpResult.metrics" size="small" style="margin-top: 8px" max-height="220">
            <el-table-column prop="key" label="指标" min-width="150" />
            <el-table-column prop="targetMean" label="目标均值" width="90" />
            <el-table-column prop="baseMean" label="基线均值" width="90" />
            <el-table-column prop="foldChangeLog2" label="log₂FC" width="80" />
            <el-table-column prop="pValue" label="p 值" width="90" />
            <el-table-column label="结论" min-width="110">
              <template #default="{ row }">
                <el-tag v-if="row.significant" size="small" effect="plain"
                        :type="row.foldChangeLog2 > 0 ? 'danger' : 'primary'">
                  {{ row.foldChangeLog2 > 0 ? "目标显著升高" : "目标显著降低" }}
                </el-tag>
                <span v-else class="sub">{{ row.sufficient ? "不显著" : "样本不足" }}</span>
              </template>
            </el-table-column>
          </el-table>
        </template>
        <el-empty v-else-if="!cmpRunning" description="同一应用两台机器一台慢——差异在哪？选好两个实例开跑" :image-size="50" />
      </el-tab-pane>
    </el-tabs>
  </el-card>

  <el-dialog v-model="confirmVisible" title="高危命令确认" width="440px">
    <p>该命令等价于在目标进程内执行任意代码：</p>
    <pre class="out">{{ command }}</pre>
    <p style="color: var(--el-color-danger)">平台策略：高危命令默认拦截，仅放行观测类命令。</p>
    <template #footer>
      <el-button @click="confirmVisible = false">取消</el-button>
      <el-button type="danger" @click="confirmVisible = false">知道了</el-button>
    </template>
  </el-dialog>

  <el-dialog v-model="phVisible" title="填入模板参数" width="480px">
    <el-form label-width="110px" size="small">
      <el-form-item v-for="p in phKeys" :key="p" :label="p">
        <el-input v-model="phValues[p]" :placeholder="phHint(p)" />
      </el-form-item>
    </el-form>
    <div class="sub">参数将替换命令中的 <code v-pre>{{占位符}}</code>；上次填值已自动带入</div>
    <template #footer>
      <el-button @click="phVisible = false">取消</el-button>
      <el-button type="primary" :disabled="phKeys.some(k => !(phValues[k] || '').trim())"
                 @click="confirmPh">填入命令</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, reactive, ref } from "vue";
import { useRouter, useRoute } from "vue-router";
import { ElMessage, ElMessageBox } from "element-plus";
import { executeDiag, getInstances, getSession, closeSession, getLocalProcesses, attachLocalProcess,
         profileStart, profileStop, artifactTicket, compareDiag,
         type DiagResultVO, type InstanceVO, type LocalProcessVO, type CompareResultVO } from "@/api/diag";
import { http, errMsg } from "@/api/http";
import { llmChatStream, type LlmDocRef } from "@/api/llm";
import { getTemplates, createTemplate, type TplVO } from "@/api/templates";
import MdView from "@/components/MdView.vue";
import DiagCharts from "@/components/DiagCharts.vue";
import VolcanoChart from "@/components/VolcanoChart.vue";
import { riskMeta, riskOfCommand, highRiskFamilies } from "@/config/risk";
import { useAuthStore } from "@/stores/auth";

const auth = useAuthStore();

interface DocHit { slug: string; title: string; category: string; risk: string; path: string }
const router = useRouter();
const route = useRoute();

const instances = ref<InstanceVO[]>([]);
const instanceId = ref("");
const command = ref("thread -n 5");
const result = ref<DiagResultVO | null>(null);
const blocked = ref(false);
const running = ref(false);
const confirmVisible = ref(false);
const session = ref<{ active?: boolean; sessionId?: string; expiresAt?: string }>({});
const cmdDocs = ref<DocHit[]>([]);
const risk = computed(() => riskOfCommand(command.value));
const remain = ref("--:--");
let timer: number | undefined;

const localProcs = ref<{ localEnabled: boolean; processes: LocalProcessVO[] }>({ localEnabled: false, processes: [] });
const attaching = ref<number | null>(null);
const localOpen = ref(false);
const managedPids = computed(() => new Set(
  instances.value.filter((i) => i.localAgent).map((i) => Number(i.instanceId.replace("local-", ""))),
));
const currentInstance = computed(() => instances.value.find((i) => i.instanceId === instanceId.value) ?? null);
const resultHeader = computed(() => {
  const id = currentInstance.value?.instanceId ?? "";
  if (id.startsWith("agent-")) return "执行结果（Agent 通道）";
  return currentInstance.value?.localAgent ? "执行结果（本机通道）" : "执行结果";
});
/** 选中态紧凑文案：短应用名 + 节点/通道（输入框宽度预算内逐级缩短应用名） */
function compactLabel(i: InstanceVO) {
  if (i.instanceId.startsWith("local-")) return `${twoSeg(i.appId)} · 本机`;
  if (!i.instanceId.startsWith("agent-")) return `${i.appId} / ${i.instanceId}`;
  const node = nodeShort(i.instanceId);
  const budget = Math.max(6, 24 - node.length - 3);
  let app = twoSeg(i.appId);
  if (app.length > budget) {
    app = oneSeg(i.appId);
  }
  if (app.length > budget) {
    app = app.slice(0, Math.max(4, budget - 1)) + "…";
  }
  return `${app} · ${node}`;
}

function channelName(i: InstanceVO) {
  return i.instanceId.startsWith("agent-") ? "Agent 节点"
    : i.instanceId.startsWith("local-") ? "本机纳管" : "演示靶场";
}

function pidOf(instanceId: string) {
  return instanceId.slice(instanceId.lastIndexOf("-") + 1);
}

/** agent-windevdemo-a624-50632 → windevdemo（去通道前缀/随机尾缀/pid） */
function nodeShort(instanceId: string) {
  return instanceId.slice("agent-".length).replace(/-[0-9]+$/, "").replace(/-[0-9a-f]{1,4}$/, "");
}

/** 末两段包名并去 jar 版本后缀：org.eclipse.equinox.launcher_1.8.0 → equinox.launcher */
function twoSeg(appId: string) {
  const s = appId.split("_")[0];
  const parts = s.split(".");
  return parts.length > 2 ? parts.slice(-2).join(".") : s;
}

/** 末一段：equinox.launcher → launcher */
function oneSeg(appId: string) {
  const parts = appId.split("_")[0].split(".");
  return parts[parts.length - 1];
}

/** 完整标识（当前实例一行 + 悬停 title） */
const fullLabel = computed(() => {
  const i = currentInstance.value;
  if (!i) return "";
  const suffix = i.instanceId.startsWith("agent-") ? " · Agent 节点"
    : i.instanceId.startsWith("local-") ? " · 本机" : "";
  return `${i.appId}/${i.instanceId}${suffix}`;
});

async function loadLocal() {
  try {
    localProcs.value = await getLocalProcesses();
  } catch {
    localProcs.value = { localEnabled: false, processes: [] };
  }
}

async function attach(pid: number) {
  attaching.value = pid;
  try {
    const ins = await attachLocalProcess(pid);
    ElMessage.success(`已纳管 ${ins.mainClass ?? ins.appId}（#${pid}），走真实通道`);
    instances.value = await getInstances();
    instanceId.value = ins.instanceId;
  } catch (e) {
    ElMessage.error(errMsg(e));
  } finally {
    attaching.value = null;
  }
}

const tplFallback: Partial<TplVO>[] = [
  { id: -1, name: "慢接口排查", kind: "diag", scene: "接口", enabled: true, builtIn: true, bindAppId: null,
    items: [{ name: "慢接口排查", command: "trace {{类名}} {{方法名}} '#cost>100'" }], placeholders: ["类名", "方法名"] },
  { id: -2, name: "CPU 飙高", kind: "diag", scene: "CPU", enabled: true, builtIn: true, bindAppId: null,
    items: [{ name: "CPU 飙高", command: "thread -n 5" }], placeholders: [] },
  { id: -3, name: "内存水位", kind: "diag", scene: "内存", enabled: true, builtIn: true, bindAppId: null,
    items: [{ name: "内存水位", command: "memory" }], placeholders: [] },
  { id: -4, name: "JVM 概览", kind: "diag", scene: "通用", enabled: true, builtIn: true, bindAppId: null,
    items: [{ name: "JVM 概览", command: "jvm" }], placeholders: [] },
  { id: -5, name: "实时面板", kind: "diag", scene: "通用", enabled: true, builtIn: true, bindAppId: null,
    items: [{ name: "实时面板", command: "dashboard -n 1" }], placeholders: [] },
  { id: -6, name: "接口观测", kind: "diag", scene: "接口", enabled: true, builtIn: true, bindAppId: null,
    items: [{ name: "接口观测", command: "watch {{类名}} {{方法名}} '{params,returnObj}' -n 100" }],
    placeholders: ["类名", "方法名"] },
];
const tpls = ref<TplVO[]>(tplFallback as TplVO[]);

async function loadTpls() {
  try {
    const list = await getTemplates("diag");
    if (list.length) tpls.value = list;
  } catch {
    // 模板中心不可用（旧版服务端）时保留内置兜底
  }
}

// ---- 模板应用（占位符填参）----
const phVisible = ref(false);
const phKeys = ref<string[]>([]);
const phValues = reactive<Record<string, string>>({});
let phTemplate: TplVO | null = null;

function applyTemplate(t: TplVO) {
  const cmd = t.items[0]?.command ?? "";
  if (!cmd) return;
  const keys = [...new Set((cmd.match(/\{\{([^{}]+)}}/g) ?? []).map(s => s.slice(2, -2).trim()))];
  if (!keys.length) {
    command.value = cmd;
    return;
  }
  phTemplate = t;
  phKeys.value = keys;
  const saved = JSON.parse(localStorage.getItem(`bianque.tplVals.${t.id}`) ?? "{}") as Record<string, string>;
  Object.assign(phValues, saved);
  keys.forEach(k => { if (!(k in phValues)) phValues[k] = ""; });
  phVisible.value = true;
}

function phHint(k: string) {
  return /类名|class/i.test(k) ? "如 com.bianque.order.OrderService"
    : /方法|method/i.test(k) ? "如 placeOrder" : `填入 ${k}`;
}

function confirmPh() {
  if (!phTemplate) return;
  let cmd = phTemplate.items[0].command;
  for (const k of phKeys.value) {
    const v = (phValues[k] ?? "").trim();
    if (v) cmd = cmd.split(`{{${k}}}`).join(v);
  }
  command.value = cmd;
  localStorage.setItem(`bianque.tplVals.${phTemplate.id}`,
    JSON.stringify(Object.fromEntries(phKeys.value.map(k => [k, (phValues[k] ?? "").trim()]))));
  phVisible.value = false;
  phTemplate = null;
}

async function saveAsTemplate() {
  const cmd = command.value.trim();
  if (!cmd) return;
  try {
    const { value } = await ElMessageBox.prompt("为这条命令起个模板名（诊断台与管理设置可复用）", "存为模板", {
      inputValue: "",
      inputPattern: /^\S.{0,31}$/,
      inputErrorMessage: "模板名需 1–32 个字符",
      confirmButtonText: "保存",
      cancelButtonText: "取消",
    });
    await createTemplate({ name: value.trim(), kind: "diag", scene: "自定义", items: [{ name: value.trim(), command: cmd }] });
    ElMessage.success(`已沉淀为模板「${value.trim()}」`);
    await loadTpls();
  } catch (e) {
    if (e !== "cancel" && e !== "close") ElMessage.error(errMsg(e));
  }
}

async function onExecute() {
  if (highRiskFamilies.has(command.value.trim().split(/\s+/)[0].toLowerCase())) {
    confirmVisible.value = true;
    return;
  }
  running.value = true;
  aiOpen.value = false;
  aiContent.value = "";
  aiReasoning.value = "";
  aiRefs.value = undefined;
  aiDone.value = false;
  try {
    result.value = await executeDiag(instanceId.value, command.value, "人工");
    blocked.value = !result.value.success;
    if (!blocked.value) ElMessage.success(`执行完成（${result.value.costMs}ms）`);
    viewMode.value = result.value?.metrics ? "chart" : "text";
  } catch (e) {
    ElMessage.error(errMsg(e));
  } finally {
    running.value = false;
  }
  await refreshSession();
}

// ---- 底部高级工具页签（命令速查 / 火焰图 / 火山图）----
const toolTab = ref("cheats");

// ---- 性能剖析（火焰图）----
const viewMode = ref<"text" | "chart">("text");

/** 诊断报告导出（Markdown）：实例/时间/命令/输出/指标摘要 */
function exportReport() {
  const r = result.value;
  if (!r) return;
  const i = currentInstance.value;
  const lines = [
    "# 扁鹊诊断报告",
    "",
    `- 实例：${fullLabel.value || instanceId.value}`,
    `- 时间：${new Date().toLocaleString()}`,
    `- 通道：${r.channel === "real" ? "真实执行" : "演示模拟"} · 耗时 ${r.costMs}ms`,
    `- 操作人：${auth.user?.displayName ?? auth.user?.username ?? "-"}（导出本机生成）`,
    "",
    "## 命令",
    "",
    "```",
    command.value,
    "```",
    "",
    "## 输出",
    "",
    "```",
    r.output,
    "```",
  ];
  if (r.metrics) {
    lines.push("", "## 结构化指标", "", "```json", JSON.stringify(r.metrics, null, 2), "```");
  }
  lines.push("", "---", "由扁鹊（BianQue）诊断台导出；火焰图等图表请于平台内查看。");
  const blob = new Blob([lines.join("\n")], { type: "text/markdown;charset=utf-8" });
  const a = document.createElement("a");
  a.href = URL.createObjectURL(blob);
  a.download = `bianque-report-${instanceId.value}-${Date.now()}.md`;
  a.click();
  URL.revokeObjectURL(a.href);
  ElMessage.success("报告已导出（Markdown）");
}

const profiling = ref(false);
const stopping = ref(false);
const profileTip = ref("");
const flameUrl = ref("");

async function startProfile() {
  profiling.value = true;
  profileTip.value = "";
  try {
    await profileStart(instanceId.value);
    profiling.value = true;
    profileTip.value = "CPU 采样中（event=cpu）……复现问题现象后点「停止并出图」";
    ElMessage.success("剖析已开始");
  } catch (e) {
    profileTip.value = "";
    ElMessage.error(errMsg(e));
  } finally {
    if (!profileTip.value) profiling.value = false;
  }
}

async function stopProfile() {
  stopping.value = true;
  try {
    const r = await profileStop(instanceId.value);
    profiling.value = false;
    profileTip.value = `产物 ${r.path}（${Math.round(r.sizeBytes / 1024)}KB）`;
    // iframe 无法携带 Bearer 头；blob 载荷会继承页面 CSP（frame-src 不含 blob:、内联脚本被
    // script-src 禁）被浏览器整体拦截——改签发一次性查看票据，iframe 直接以同源 URL 渲染
    const t = await artifactTicket(r.artifactId);
    flameUrl.value = `${t.url}?bt=${encodeURIComponent(t.ticket)}`;
    ElMessage.success("火焰图已生成");
  } catch (e) {
    ElMessage.error(errMsg(e));
  } finally {
    stopping.value = false;
  }
}

// ---- 双实例对比诊断（火山图）----
const cmpTarget = ref("");
const cmpBase = ref("");
const cmpRounds = ref(5);
const cmpRunning = ref(false);
const cmpResult = ref<CompareResultVO | null>(null);
const cmpHasSufficient = computed(() => (cmpResult.value?.metrics ?? []).some(m => m.sufficient));

async function runCompare() {
  cmpRunning.value = true;
  cmpResult.value = null;
  try {
    cmpResult.value = await compareDiag(cmpTarget.value, cmpBase.value, cmpRounds.value);
    if (cmpResult.value.errors?.length) {
      ElMessage.warning(`对比完成：${cmpResult.value.metrics.length} 项指标，另有 ${cmpResult.value.errors.length} 条命令失败`);
    } else {
      ElMessage.success(`对比完成：${cmpResult.value.metrics.length} 项指标`);
    }
  } catch (e) {
    ElMessage.error(errMsg(e));
  } finally {
    cmpRunning.value = false;
  }
}

// ---- 执行结果就地 AI 分析（v1.1：AI 贯穿诊断台）----
const aiOpen = ref(false);
const aiRunning = ref(false);
const aiDone = ref(false);
const aiContent = ref("");
const aiReasoning = ref("");
const aiThinkOpen = ref(true);
const aiRefs = ref<LlmDocRef[] | undefined>(undefined);
let aiAbort: AbortController | undefined;

function runAi() {
  const output = result.value?.output?.trim();
  if (!output) return;
  aiOpen.value = true;
  aiRunning.value = true;
  aiDone.value = false;
  aiContent.value = "";
  aiReasoning.value = "";
  aiRefs.value = undefined;
  aiThinkOpen.value = true;
  const q = `线上实例「${fullLabel.value || instanceId.value}」执行诊断命令「${command.value}」得到如下输出，请分析：`
    + "①输出反映的关键状态与异常点；②可能的根因方向；③下一步排查建议（给出具体只读 Arthas 命令）。简体中文分点作答。";
  aiAbort = new AbortController();
  llmChatStream(
    { question: q, history: [], context: output.slice(0, 12000), instanceId: instanceId.value },
    {
      onReasoning: t => (aiReasoning.value += t),
      onContent: t => (aiContent.value += t),
      onDone: out => {
        aiContent.value = out.content || aiContent.value;
        aiRefs.value = out.refs?.length ? out.refs : undefined;
        aiDone.value = true;
      },
      onError: msg => {
        aiContent.value += (aiContent.value ? "\n\n" : "") + "调用失败：" + msg;
        aiDone.value = true;
      },
    },
    { signal: aiAbort.signal },
  )
    .catch(e => {
      if ((e as Error).name !== "AbortError") {
        aiContent.value += (aiContent.value ? "\n\n" : "") + "调用失败：" + errMsg(e);
      }
    })
    .finally(() => {
      aiRunning.value = false;
      aiThinkOpen.value = false;
    });
}

function stopAi() {
  aiAbort?.abort();
}

/** 把本次输出带到 AI 问诊台作为上下文继续追问 */
function goAsk() {
  sessionStorage.setItem("bianque.aiHandoff", JSON.stringify({
    context: result.value?.output ?? "",
    origin: `诊断台（${command.value}）`,
  }));
  router.push("/ai");
}

async function refreshSession() {
  session.value = await getSession(instanceId.value);
}

async function closeSessionNow() {
  if (session.value.sessionId) await closeSession(session.value.sessionId);
  ElMessage.success("会话已回收（reset + stop）");
  await refreshSession();
}

function goDoc(row: DocHit | string) {
  const path = typeof row === "string" ? row : row.path;
  router.push({ path: "/docs", query: { page: path } });
}

function tick() {
  if (!session.value.expiresAt) { remain.value = "--:--"; return; }
  const ms = new Date(session.value.expiresAt).getTime() - Date.now();
  if (ms <= 0) { remain.value = "已超时"; return; }
  const m = Math.floor(ms / 60000), s = Math.floor((ms % 60000) / 1000);
  remain.value = `${String(m).padStart(2, "0")}:${String(s).padStart(2, "0")}`;
}

onMounted(async () => {
  instances.value = await getInstances();
  loadTpls();
  // 支持从节点管理页「去诊断」跳转带入实例；从全局搜索带入命令
  const fromQuery = String(route.query.instanceId ?? "");
  instanceId.value = instances.value.some((i) => i.instanceId === fromQuery)
    ? fromQuery
    : instances.value.find((i) => i.online)?.instanceId ?? "";
  const fromCmd = String(route.query.cmd ?? "");
  if (fromCmd) command.value = fromCmd;
  loadLocal();
  try {
    const catalog = await http.get("/docs/catalog") as unknown as { pages: DocHit[] };
    cmdDocs.value = (catalog.pages ?? []).filter((p: DocHit) =>
      ["thread", "memory", "jvm", "watch", "trace", "tt", "dashboard", "profiler", "sc"].includes(p.slug));
  } catch {
    cmdDocs.value = [];
  }
  await refreshSession();
  timer = window.setInterval(tick, 1000);
});
onUnmounted(() => window.clearInterval(timer));
</script>

<style scoped>
.sub { font-size: 12px; color: #909399; }
.row { display: flex; align-items: center; gap: 10px; }
.result-body { min-height: 160px; }
.tpl { cursor: pointer; }
.tpl-ph { margin-left: 4px; font-size: 10px; color: var(--el-color-primary); }
.lproc { display: flex; align-items: center; gap: 6px; margin-bottom: 6px; }
.ellip { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
/* 窄栏下拉：小字号容纳「应用 · 节点」紧凑标识 */
.instance-select :deep(.el-select__wrapper),
.instance-select :deep(.el-input__inner) { font-size: 12px; }
.lproc .cmd { flex: 1; font-size: 11px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.lproc .el-button { margin-left: auto; }
/* 左栏「本机进程纳管」折叠小节（与 AI 思考过程同款箭头） */
.sec-hd {
  display: flex; align-items: center; gap: 6px; margin-top: 10px; padding: 7px 0 2px;
  border-top: 1px solid var(--el-border-color-lighter);
  font-size: 13px; color: var(--el-text-color-regular); cursor: pointer; user-select: none;
}
.sec-hd:hover { color: var(--el-color-primary); }
.chev.open { transform: rotate(180deg); }
/* 底部高级工具页签卡：页签头与内容间距收紧 */
.tools-card :deep(.el-tabs__header) { margin-bottom: 10px; }
/* 就地 AI 分析 */
.ai-row { display: flex; align-items: center; gap: 8px; margin-top: 10px; }
.ai-panel {
  margin-top: 10px; padding: 10px 12px; font-size: 13px; line-height: 1.6;
  background: #f8fafc; border: 1px solid #eef1f6; border-radius: 8px;
}
.think { margin: -2px 0 8px; border: 1px dashed #e2e8f2; border-radius: 8px; background: #fbfcfe; }
.think-hd { display: flex; align-items: center; gap: 6px; padding: 5px 10px; font-size: 12px; color: var(--el-text-color-secondary); cursor: pointer; user-select: none; }
.think-hd:hover { color: var(--el-color-primary); }
.think.open .chev { transform: rotate(180deg); }
.chev { flex: none; transition: transform 0.2s; }
.reason { padding: 4px 12px 9px; font-size: 12px; color: var(--el-text-color-secondary); white-space: pre-wrap; max-height: 220px; overflow-y: auto; }
.refs { margin-top: 8px; display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.ref { cursor: pointer; }
.ref:hover { color: var(--el-color-primary); border-color: var(--el-color-primary-light-5); }
.card-hd { display: flex; align-items: center; justify-content: space-between; }
.flame-box { margin-top: 10px; border: 1px solid #eef1f6; border-radius: 8px; overflow: hidden; }
.flame-frame { width: 100%; height: 320px; border: 0; display: block; background: #fff; }
</style>

<style>
/* 实例下拉的选项在 teleported popper 里（挂在 body 下），须用全局样式；
   两行明细条目取消固定 34px 高，自适应 + 上下留白拉开间距 */
.el-popper.opt-popper .el-select-dropdown__item {
  height: auto;
  line-height: 1.4;
  padding: 7px 20px;
  margin: 3px 0;
  border-radius: 6px;
}
.el-popper.opt-popper .opt { padding: 0; }
.el-popper.opt-popper .opt-t { font-size: 13px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.el-popper.opt-popper .opt-id { color: var(--el-text-color-secondary); font-size: 11px; }
.el-popper.opt-popper .opt-s { font-size: 11px; color: var(--el-text-color-secondary); margin-top: 3px; }
</style>
