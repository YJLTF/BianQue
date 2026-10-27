<template>
  <el-row :gutter="12">
    <el-col :span="5">
      <el-card shadow="never" header="应用 / 实例">
        <el-select v-model="instanceId" filterable style="width: 100%">
          <el-option v-for="i in instances" :key="i.instanceId"
                     :label="`${i.appId}/${i.instanceId}`" :value="i.instanceId"
                     :disabled="!i.online" />
        </el-select>
        <div class="sub" style="margin-top: 8px">
          会话：{{ session.active ? `剩余 ${remain}（超时自动 reset+stop）` : "未开启" }}
        </div>
        <el-button size="small" :disabled="!session.active" style="margin-top: 8px" @click="closeSessionNow">
          结束并重置
        </el-button>
      </el-card>
    </el-col>

    <el-col :span="10">
      <el-card shadow="never" header="场景模板（一键填充）">
        <el-space wrap>
          <el-tag v-for="t in templates" :key="t.label" class="tpl" type="info" effect="plain"
                  style="cursor: pointer" @click="applyTemplate(t)">{{ t.label }}</el-tag>
        </el-space>
      </el-card>
      <el-card shadow="never" header="命令构建（所见即所执行）" style="margin-top: 12px">
        <el-input v-model="command" type="textarea" :rows="3" placeholder="watch 类名 方法名 '{params,returnObj}' -n 100" />
        <div style="margin-top: 8px" class="row">
          <el-tag :type="risk.type" effect="plain">{{ risk.label }}</el-tag>
          <el-button type="primary" size="small" :disabled="!instanceId || !command.trim()" @click="onExecute">
            执行诊断
          </el-button>
          <span class="sub">高危命令将被平台黑名单拦截并审计</span>
        </div>
      </el-card>
      <el-card shadow="never" header="执行结果" style="margin-top: 12px">
        <el-alert v-if="blocked" type="error" :closable="false" :title="result?.error" show-icon />
        <pre v-else-if="result" class="out">{{ result.output }}</pre>
        <el-empty v-else description="执行后在此查看结构化输出（原始输出全程可查）" :image-size="60" />
      </el-card>
    </el-col>

    <el-col :span="9">
      <el-card shadow="never" header="文档即命令（点击查看官方文档）">
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
                title="增强类命令（watch/trace 等）由平台强制限次限频；会话结束自动 reset+stop，防止增强残留" />
    </el-col>
  </el-row>

  <el-dialog v-model="confirmVisible" title="高危命令确认" width="440px">
    <p>该命令等价于在目标进程内执行任意代码：</p>
    <pre class="out">{{ command }}</pre>
    <p style="color: var(--el-color-danger)">V0.1.0 平台策略：高危命令默认拦截，仅观测类命令放行。</p>
    <template #footer>
      <el-button @click="confirmVisible = false">取消</el-button>
      <el-button type="danger" @click="confirmVisible = false">知道了</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from "vue";
import { useRouter } from "vue-router";
import { ElMessage } from "element-plus";
import { executeDiag, getInstances, getSession, closeSession, type DiagResultVO, type InstanceVO } from "@/api/diag";
import { riskMeta, riskOfCommand, highRiskFamilies } from "@/config/risk";

interface DocHit { slug: string; title: string; category: string; risk: string; path: string }
const router = useRouter();

const instances = ref<InstanceVO[]>([]);
const instanceId = ref("");
const command = ref("thread -n 5");
const result = ref<DiagResultVO | null>(null);
const blocked = ref(false);
const confirmVisible = ref(false);
const session = ref<{ active?: boolean; sessionId?: string; expiresAt?: string }>({});
const cmdDocs = ref<DocHit[]>([]);
const risk = computed(() => riskOfCommand(command.value));
const remain = ref("--:--");
let timer: number | undefined;

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
  result.value = await executeDiag(instanceId.value, command.value, "人工");
  blocked.value = !result.value.success;
  if (!blocked.value) ElMessage.success(`执行完成（${result.value.costMs}ms）`);
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
  instanceId.value = instances.value.find((i) => i.online)?.instanceId ?? "";
  const catalog = await fetch("/api/docs/catalog").then((r) => r.json());
  cmdDocs.value = (catalog.pages ?? []).filter((p: DocHit) =>
    ["thread", "memory", "jvm", "watch", "trace", "tt", "dashboard", "profiler", "sc"].includes(p.slug));
  await refreshSession();
  timer = window.setInterval(tick, 1000);
});
onUnmounted(() => window.clearInterval(timer));
</script>

<style scoped>
.sub { font-size: 12px; color: #909399; }
.row { display: flex; align-items: center; gap: 10px; }
.out { background: #f5f7fa; border: 1px solid #e4e7ed; border-radius: 6px; padding: 8px 10px;
       font-size: 12px; white-space: pre-wrap; overflow-wrap: anywhere; max-height: 320px; overflow-y: auto; margin: 0; }
.tpl { cursor: pointer; }
</style>
