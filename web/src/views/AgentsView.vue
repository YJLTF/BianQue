<template>
  <div v-loading="loading">
    <el-row :gutter="12">
      <el-col :span="8">
        <el-card shadow="never" class="stat">
          <div class="stat-top">
            <span class="tile indigo"><el-icon><Monitor /></el-icon></span>
            <span class="k">接入节点</span>
          </div>
          <div class="v">{{ list?.total ?? "-" }}</div>
          <div class="d">部署于目标主机的 bq-agent（平台版）</div>
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card shadow="never" class="stat">
          <div class="stat-top">
            <span class="tile jade"><el-icon><Connection /></el-icon></span>
            <span class="k">在线节点</span>
          </div>
          <div class="v">{{ list?.online ?? "-" }}</div>
          <div class="d">心跳窗口 20s · 断连自动摘除实例</div>
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card shadow="never" class="stat">
          <div class="stat-top">
            <span class="tile amber"><el-icon><Cpu /></el-icon></span>
            <span class="k">接管 JVM 进程</span>
          </div>
          <div class="v">{{ list?.processes ?? "-" }}</div>
          <div class="d">心跳自动上报 · 诊断台可直接执行</div>
        </el-card>
      </el-col>
    </el-row>

    <el-card shadow="never" header="接入节点" style="margin-top: 12px">
      <el-table :data="list?.nodes ?? []" size="small">
        <el-table-column type="expand">
          <template #default="{ row }">
            <div class="procs">
              <el-table :data="row.processes" size="small">
                <el-table-column prop="pid" label="PID" width="110" />
                <el-table-column prop="mainClass" label="主类 / 主 Jar">
                  <template #default="{ row: p }"><code>{{ p.mainClass }}</code></template>
                </el-table-column>
                <el-table-column prop="instanceId" label="平台实例">
                  <template #default="{ row: p }"><code>{{ p.instanceId }}</code></template>
                </el-table-column>
                <el-table-column label="诊断" width="120">
                  <template #default="{ row: p }">
                    <el-button size="small" text type="primary" @click="goClinic(p.instanceId)">
                      去诊断 →
                    </el-button>
                  </template>
                </el-table-column>
              </el-table>
              <el-empty v-if="!row.processes.length" description="该节点未发现 JVM 进程" :image-size="48" />
            </div>
          </template>
        </el-table-column>
        <el-table-column label="节点" min-width="170">
          <template #default="{ row }">
            <span class="pulse-dot" :class="{ off: !row.online }" />
            <code>{{ row.agentId }}</code>
          </template>
        </el-table-column>
        <el-table-column label="主机" min-width="150">
          <template #default="{ row }">
            {{ row.hostname }} <span class="sub">({{ row.host }})</span>
          </template>
        </el-table-column>
        <el-table-column prop="os" label="系统" min-width="130" show-overflow-tooltip />
        <el-table-column prop="javaVersion" label="JDK" width="70" />
        <el-table-column prop="agentVersion" label="Agent" width="80" />
        <el-table-column label="进程" width="70" align="right">
          <template #default="{ row }">{{ row.processes.length }}</template>
        </el-table-column>
        <el-table-column label="心跳" width="110">
          <template #default="{ row }">{{ hhmmss(row.lastHeartbeat) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="row.online ? 'success' : 'info'" effect="plain" round>
              {{ row.online ? "在线" : "离线" }}
            </el-tag>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="list && !list.nodes.length"
                description="尚无节点接入：按下方指引在目标主机启动 bq-agent" :image-size="70" />
    </el-card>

    <el-card shadow="never" header="节点接入指引（平台版 Agent）" style="margin-top: 12px">
      <el-alert type="info" :closable="false" show-icon style="margin-bottom: 14px"
                title="架构：Agent 部署在目标主机，纯出站 HTTP 与平台通信（默认 5s 心跳）——目标主机无需开放任何入站端口；命令经平台命令单下发，节点用 arthas attach 执行并回传。" />
      <div class="steps">
        <div v-for="(s, i) in install?.steps ?? []" :key="i" class="step">
          <span class="no">{{ i + 1 }}</span>
          <div class="step-body">
            <div class="step-label">{{ stepLabels[i] }}</div>
            <div class="cmdline">
              <code class="cmd">{{ s }}</code>
              <el-button size="small" text type="primary" @click="copy(s, i)">复制</el-button>
            </div>
          </div>
        </div>
      </div>
      <div class="notes">
        <div>· 令牌：{{ tokenNote }}</div>
        <div>· 节点要求：JDK 11+（目标 JVM 与节点 JDK 同用户可 attach）；arthas-boot.jar 与 bq-agent.jar 同目录</div>
        <div>· 会话治理：节点侧空闲 30 分钟自动 stop；平台侧会话回收时下发 stop，防增强残留</div>
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from "vue";
import { useRouter } from "vue-router";
import { ElMessage } from "element-plus";
import { getAgentInstall, getAgentNodes, type AgentInstallVO, type AgentListVO } from "@/api/agent";

const router = useRouter();
const list = ref<AgentListVO | null>(null);
const install = ref<AgentInstallVO | null>(null);
const loading = ref(true);
let timer: number | undefined;

const stepLabels = ["下载节点程序", "放置 arthas-boot.jar", "启动 Agent（注册即上线）"];

const tokenNote = computed(() => {
  if (!install.value) return "…";
  if (!install.value.tokenConfigured) return "未配置（bianque.agent.token 为空，仅限内网演示；生产必配）";
  return install.value.token ? `已配置，当前为 ${install.value.token}` : "已配置（完整令牌仅 admin:user 权限可见）";
});

function hhmmss(iso: string) {
  if (!iso) return "—";
  const d = new Date(iso);
  const p = (n: number) => String(n).padStart(2, "0");
  return `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
}

async function copy(text: string, i: number) {
  try {
    await navigator.clipboard.writeText(text);
    ElMessage.success(`步骤 ${i + 1} 已复制`);
  } catch {
    ElMessage.warning("复制失败，请手动选择文本");
  }
}

function goClinic(instanceId: string) {
  router.push({ path: "/clinic", query: { instanceId } });
}

async function load() {
  try {
    const [l, ins] = await Promise.all([getAgentNodes(), getAgentInstall()]);
    list.value = l;
    install.value = ins;
  } finally {
    loading.value = false;
  }
}

onMounted(() => {
  load();
  timer = window.setInterval(load, 5000);
});
onUnmounted(() => window.clearInterval(timer));
</script>

<style scoped>
.stat-top { display: flex; align-items: center; gap: 8px; }
.tile {
  width: 28px;
  height: 28px;
  border-radius: 8px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  flex: none;
}
.tile.indigo { background: linear-gradient(135deg, #4f46e5, #8b5cf6); }
.tile.jade { background: linear-gradient(135deg, #0d9488, #14b8a6); }
.tile.amber { background: linear-gradient(135deg, #d97706, #f59e0b); }
.k { font-size: 13px; color: var(--el-text-color-secondary); }
.v { font-size: 26px; font-weight: 700; color: var(--el-text-color-primary); margin-top: 6px; }
.d { font-size: 12px; color: var(--el-text-color-secondary); margin-top: 2px; }
.sub { color: var(--el-text-color-secondary); font-size: 12px; }
.procs { padding: 4px 12px 8px 40px; }
.steps { display: flex; flex-direction: column; gap: 12px; }
.step { display: flex; gap: 12px; align-items: flex-start; }
.no {
  width: 24px;
  height: 24px;
  border-radius: 50%;
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
  font-size: 13px;
  font-weight: 600;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: none;
  margin-top: 2px;
}
.step-body { flex: 1; min-width: 0; }
.step-label { font-size: 13px; font-weight: 600; color: var(--el-text-color-primary); margin-bottom: 4px; }
.cmdline {
  display: flex;
  align-items: center;
  gap: 8px;
  background: #0f172a;
  border-radius: 8px;
  padding: 8px 10px;
}
.cmd {
  flex: 1;
  background: transparent;
  color: #d7e0f0;
  padding: 0;
  font-family: var(--bq-mono, Consolas, monospace);
  font-size: 12px;
  word-break: break-all;
  white-space: pre-wrap;
}
.notes { margin-top: 14px; display: flex; flex-direction: column; gap: 4px; font-size: 12px; color: var(--el-text-color-secondary); }
</style>
