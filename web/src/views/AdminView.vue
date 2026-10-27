<template>
  <el-row :gutter="12">
    <el-col :span="12">
      <el-card shadow="never" header="安全基线（平台统一下发到 agent）">
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item label="端口绑定">
            <el-tag size="small" type="success" effect="plain">127.0.0.1（仅本地）</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="强制认证">
            <el-tag size="small" :type="baseline.authRequired ? 'success' : 'danger'" effect="plain">
              {{ baseline.authRequired ? "已启用" : "未启用" }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="会话超时">{{ baseline.sessionTimeoutMinutes }} 分钟（自动 reset+stop）</el-descriptions-item>
          <el-descriptions-item label="高危黑名单">
            <el-tag v-for="c in baseline.blockedCommands ?? []" :key="c" size="small" type="danger" effect="plain" style="margin: 0 4px 4px 0">
              {{ c }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="审计">
            <el-tag size="small" :type="baseline.auditEnabled ? 'success' : 'info'" effect="plain">
              {{ baseline.auditEnabled ? "全操作留痕" : "关闭" }}
            </el-tag>
          </el-descriptions-item>
        </el-descriptions>
      </el-card>
      <el-card shadow="never" header="RBAC 权限矩阵（命令族 × 操作员）" style="margin-top: 12px">
        <el-table :data="rbac" size="small">
          <el-table-column prop="family" label="命令族" min-width="220" />
          <el-table-column prop="access" label="操作员" width="120" />
        </el-table>
      </el-card>
      <el-card shadow="never" header="用户与角色（RBAC 五表）" style="margin-top: 12px">
        <el-table :data="users" size="small" v-loading="usersLoading">
          <el-table-column prop="username" label="账号" width="80" />
          <el-table-column prop="displayName" label="姓名" width="120" />
          <el-table-column label="角色" width="96">
            <template #default="{ row }">
              <el-tag size="small" :type="row.roles?.[0] === 'ADMIN' ? 'danger' : (row.roles?.[0] === 'OPERATOR' ? 'warning' : 'info')" effect="plain">
                {{ row.roles?.[0] ?? "-" }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="权限点" min-width="180">
            <template #default="{ row }">
              <el-tag v-for="p in row.permissions ?? []" :key="p" size="small" effect="plain" style="margin: 0 4px 4px 0">
                {{ p }}
              </el-tag>
            </template>
          </el-table-column>
        </el-table>
      </el-card>
    </el-col>
    <el-col :span="12">
      <el-card shadow="never" header="Agent 安装向导（Linux · systemd + tunnel 纳管）">
        <pre class="out">{{ linux }}</pre>
        <el-button size="small" style="margin-top: 6px" @click="copy(linux)">复制</el-button>
      </el-card>
      <el-card shadow="never" header="Agent 安装向导（Windows · as-service.bat 注册服务）" style="margin-top: 12px">
        <pre class="out">{{ win }}</pre>
        <el-button size="small" style="margin-top: 6px" @click="copy(win)">复制</el-button>
      </el-card>
      <el-card shadow="never" header="MCP 个人版接入（零开发）" style="margin-top: 12px">
        <code>http://127.0.0.1:8563/mcp</code>（STREAMABLE HTTP · Bearer Token · 29 个诊断工具）
        <el-button size="small" style="margin-left: 8px" @click="copy('http://127.0.0.1:8563/mcp')">复制端点</el-button>
      </el-card>
    </el-col>
  </el-row>
</template>

<script setup lang="ts">
import { onMounted, ref } from "vue";
import { ElMessage } from "element-plus";

interface Baseline { bindLocalOnly: boolean; authRequired: boolean; sessionTimeoutMinutes: number; blockedCommands: string[]; auditEnabled: boolean }
interface PlatformUser { username: string; displayName: string; enabled: boolean; roles: string[]; permissions: string[] }

const baseline = ref<Baseline>({ bindLocalOnly: true, authRequired: true, sessionTimeoutMinutes: 30, blockedCommands: [], auditEnabled: true });
const users = ref<PlatformUser[]>([]);
const usersLoading = ref(true);

const linux = `unzip arthas-packaging-4.3.5-bin.zip -d /opt/arthas
./as.sh --tunnel-server ws://bq-center:7777/ws \\
  --agent-id \$(hostname)-\$UUID --app-name order-app \\
  --attach-only --execution-timeout 60
# systemd: Restart=always（异常自愈）`;

const win = `as-service.bat --service --tunnel-server ws://bq-center:7777/ws --app-name order-app`;

const rbac = [
  { family: "观测类 dashboard/jvm/memory/thread…", access: "✓ 允许" },
  { family: "增强观测 watch/trace/tt/monitor", access: "✓ 允许（限次限频）" },
  { family: "剖析 profiler/jfr", access: "✓ 允许" },
  { family: "高危 ognl/redefine/heapdump…", access: "✗ 黑名单拦截" },
];

async function copy(text: string) {
  try {
    await navigator.clipboard.writeText(text);
    ElMessage.success("已复制");
  } catch {
    ElMessage.warning("复制失败，请手动选择");
  }
}

onMounted(async () => {
  baseline.value = await fetch("/api/baseline").then((r) => r.json());
  try {
    users.value = await fetch("/api/admin/users", {
      headers: { Authorization: `Bearer ${JSON.parse(localStorage.getItem("bianque.auth") || "{}").token || ""}` },
    }).then((r) => (r.ok ? r.json() : []));
  } catch {
    users.value = [];
  } finally {
    usersLoading.value = false;
  }
});
</script>

<style scoped>
</style>
