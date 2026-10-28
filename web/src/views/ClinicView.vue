<template>
  <el-row :gutter="12">
    <el-col :span="5">
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
      </el-card>
      <el-card shadow="never" header="本机进程纳管" style="margin-top: 12px">
        <div v-if="localProcs.localEnabled">
          <div v-for="p in localProcs.processes" :key="p.pid" class="lproc">
            <code class="cmd">{{ p.mainClass }}</code>
            <span class="sub">#{{ p.pid }}</span>
            <el-button v-if="!managedPids.has(p.pid)" size="small" type="primary" plain
                       :loading="attaching === p.pid" @click="attach(p.pid)">纳管</el-button>
            <el-tag v-else size="small" type="success" effect="plain">已纳管</el-tag>
          </div>
          <div class="sub" style="margin-top: 6px">纳管后可对其执行真实 Arthas 命令</div>
        </div>
        <div v-else class="sub">本机纳管未启用（local-enabled=false）</div>
      </el-card>
    </el-col>

    <el-col :span="10">
      <el-card shadow="never" header="场景模板">
        <el-space wrap>
          <el-tag v-for="t in templates" :key="t.label" class="tpl" type="info" effect="plain"
                  style="cursor: pointer" @click="applyTemplate(t)">{{ t.label }}</el-tag>
        </el-space>
      </el-card>
      <el-card shadow="never" header="命令构建" style="margin-top: 12px">
        <el-input v-model="command" type="textarea" :rows="3" placeholder="watch 类名 方法名 '{params,returnObj}' -n 100" />
        <div style="margin-top: 8px" class="row">
          <el-tag :type="risk.type" effect="plain">{{ risk.label }}</el-tag>
          <el-button type="primary" size="small" :loading="running"
                     :disabled="!instanceId || !command.trim()" @click="onExecute">
            执行诊断
          </el-button>
          <el-tag v-if="result && !blocked" size="small" :type="result.channel === 'real' ? 'success' : 'info'" effect="plain">
            {{ result.channel === "real" ? "真实执行" : "演示模拟" }} · {{ result.costMs }}ms
          </el-tag>
          <span class="sub">高危命令将被平台黑名单拦截并审计</span>
        </div>
      </el-card>
      <el-card shadow="never" :header="resultHeader" style="margin-top: 12px">
        <div v-loading="running" element-loading-text="命令执行中，请稍候…" class="result-body">
          <el-alert v-if="blocked" type="error" :closable="false" :title="result?.error" show-icon />
          <pre v-else-if="result" class="out">{{ result.output }}</pre>
          <el-empty v-else description="选择实例与命令，执行后在此查看输出" :image-size="60" />
        </div>
      </el-card>
    </el-col>

    <el-col :span="9">
      <el-card shadow="never" header="命令速查">
        <el-table :data="cmdDocs" size="small" @row-click="goDoc">
          <el-table-column prop="slug" label="命令" width="100" />
          <el-table-column prop="risk" label="风险" width="140">
            <template #default="{ row }">
              <el-tag size="small" :type="riskMeta[row.risk]?.type ?? 'info'" effect="plain">
                {{ riskMeta[row.risk]?.label ?? row.risk }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="path" label="文档" min-width="140" />
        </el-table>
      </el-card>
      <el-alert type="info" :closable="false" style="margin-top: 12px"
                title="watch / trace 等增强命令已限次限频；会话结束自动 reset+stop，防增强残留" />
    </el-col>
  </el-row>

  <el-dialog v-model="confirmVisible" title="高危命令确认" width="440px">
    <p>该命令等价于在目标进程内执行任意代码：</p>
    <pre class="out">{{ command }}</pre>
    <p style="color: var(--el-color-danger)">平台策略：高危命令默认拦截，仅放行观测类命令。</p>
    <template #footer>
      <el-button @click="confirmVisible = false">取消</el-button>
      <el-button type="danger" @click="confirmVisible = false">知道了</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from "vue";
import { useRouter, useRoute } from "vue-router";
import { ElMessage } from "element-plus";
import { executeDiag, getInstances, getSession, closeSession, getLocalProcesses, attachLocalProcess,
         type DiagResultVO, type InstanceVO, type LocalProcessVO } from "@/api/diag";
import { http, errMsg } from "@/api/http";
import { riskMeta, riskOfCommand, highRiskFamilies } from "@/config/risk";

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

const templates = [
  { label: "慢接口排查", cmd: "trace com.bianque.order.OrderService placeOrder '#cost>100'" },
  { label: "CPU 飙高", cmd: "thread -n 5" },
  { label: "内存水位", cmd: "memory" },
  { label: "JVM 概览", cmd: "jvm" },
  { label: "实时面板", cmd: "dashboard -n 1" },
  { label: "接口观测", cmd: "watch com.bianque.order.OrderService placeOrder '{params,returnObj}' -n 100" },
];

async function onExecute() {
  if (highRiskFamilies.has(command.value.trim().split(/\s+/)[0].toLowerCase())) {
    confirmVisible.value = true;
    return;
  }
  running.value = true;
  try {
    result.value = await executeDiag(instanceId.value, command.value, "人工");
    blocked.value = !result.value.success;
    if (!blocked.value) ElMessage.success(`执行完成（${result.value.costMs}ms）`);
  } catch (e) {
    ElMessage.error(errMsg(e));
  } finally {
    running.value = false;
  }
  await refreshSession();
}

async function refreshSession() {
  session.value = await getSession(instanceId.value);
}

async function closeSessionNow() {
  if (session.value.sessionId) await closeSession(session.value.sessionId);
  ElMessage.success("会话已回收（reset + stop）");
  await refreshSession();
}

function applyTemplate(t: { cmd: string }) {
  command.value = t.cmd;
}

function goDoc(row: DocHit) {
  router.push({ path: "/docs", query: { page: row.path } });
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
.lproc { display: flex; align-items: center; gap: 6px; margin-bottom: 6px; }
.ellip { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
/* 窄栏下拉：小字号容纳「应用 · 节点」紧凑标识 */
.instance-select :deep(.el-select__wrapper),
.instance-select :deep(.el-input__inner) { font-size: 12px; }
.lproc .cmd { flex: 1; font-size: 11px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.lproc .el-button { margin-left: auto; }
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
