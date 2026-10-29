<template>
  <el-row :gutter="12">
    <el-col :span="12">
      <el-card shadow="never" header="安全基线">
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
      <el-card shadow="never" header="权限矩阵（命令族 × 角色）" style="margin-top: 12px">
        <el-table :data="rbac" size="small" class="perm-table">
          <el-table-column prop="family" label="命令族" min-width="240" />
          <el-table-column prop="access" label="操作员" width="150" />
        </el-table>
      </el-card>
    </el-col>
    <el-col :span="12">
      <el-card shadow="never" header="Agent 安装（Linux · systemd）">
        <pre class="out">{{ linux }}</pre>
        <el-button size="small" style="margin-top: 6px" @click="copy(linux)">复制</el-button>
      </el-card>
      <el-card shadow="never" header="Agent 安装（Windows · 注册服务）" style="margin-top: 12px">
        <pre class="out">{{ win }}</pre>
        <el-button size="small" style="margin-top: 6px" @click="copy(win)">复制</el-button>
      </el-card>
      <el-card shadow="never" header="MCP 接入" style="margin-top: 12px">
        <code>http://127.0.0.1:8563/mcp</code>（Bearer 认证 · 29 个诊断工具）
        <el-button size="small" style="margin-left: 8px" @click="copy('http://127.0.0.1:8563/mcp')">复制端点</el-button>
      </el-card>
    </el-col>
  </el-row>

  <el-card shadow="never" style="margin-top: 12px">
    <template #header>
      <div class="card-hd">
        <span>用户与角色</span>
        <el-button v-if="canManage" size="small" type="primary" plain @click="createOpen = true">新增用户</el-button>
      </div>
    </template>
    <el-table :data="users" size="small" v-loading="usersLoading" class="users-table">
      <el-table-column prop="username" label="账号" width="120" />
      <el-table-column prop="displayName" label="姓名" min-width="140" show-overflow-tooltip />
      <el-table-column label="状态" width="80">
        <template #default="{ row }">
          <el-tag size="small" :type="row.enabled ? 'success' : 'info'" effect="plain">
            {{ row.enabled ? "启用" : "停用" }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="角色" width="140">
        <template #default="{ row }">
          <el-select v-if="canManage" :model-value="row.roles?.[0]" size="small" style="width: 100%"
                     :disabled="row.username === 'admin'" @change="(v: string) => changeRole(row, v)">
            <el-option v-for="r in ROLES" :key="r.v" :value="r.v" :label="r.l" />
          </el-select>
          <el-tag v-else size="small" effect="plain">{{ row.roles?.[0] ?? "-" }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="权限点" min-width="220" class-name="wrap-cell">
        <template #default="{ row }">
          <el-tag v-for="p in row.permissions ?? []" :key="p" size="small" effect="plain" style="margin: 0 4px 4px 0">
            {{ p }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column v-if="canManage" label="操作" width="310">
        <template #default="{ row }">
          <el-button size="small" text type="primary" @click="rename(row)">改名</el-button>
          <el-button size="small" text type="primary" @click="resetPwd(row)">重置密码</el-button>
          <el-button size="small" text :type="row.enabled ? 'danger' : 'success'"
                     :disabled="row.username === 'admin'" @click="toggleEnabled(row)">
            {{ row.enabled ? "停用" : "启用" }}
          </el-button>
          <el-button size="small" text type="danger"
                     :disabled="row.username === 'admin' || row.username === auth.user?.username"
                     title="内置 admin 与当前登录账号不可删除"
                     @click="removeUser(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
  </el-card>

  <el-dialog v-model="createOpen" title="新增用户" width="420px">
    <el-form label-width="80px">
      <el-form-item label="账号">
        <el-input v-model="form.username" placeholder="字母/数字/_/-，2–32 位" />
      </el-form-item>
      <el-form-item label="姓名">
        <el-input v-model="form.displayName" placeholder="显示名称（可选）" />
      </el-form-item>
      <el-form-item label="初始密码">
        <el-input v-model="form.password" type="password" show-password placeholder="至少 6 位" />
      </el-form-item>
      <el-form-item label="角色">
        <el-select v-model="form.role" style="width: 100%">
          <el-option v-for="r in ROLES" :key="r.v" :value="r.v" :label="r.l" />
        </el-select>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="createOpen = false">取消</el-button>
      <el-button type="primary" :loading="creating" @click="createUser">创建</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { http, errMsg } from "@/api/http";
import { useAuthStore } from "@/stores/auth";

const auth = useAuthStore();
const canManage = computed(() => auth.can("admin:user"));

interface Baseline { bindLocalOnly: boolean; authRequired: boolean; sessionTimeoutMinutes: number; blockedCommands: string[]; auditEnabled: boolean }
interface PlatformUser { username: string; displayName: string; enabled: boolean; roles: string[]; permissions: string[] }

const ROLES = [
  { v: "ADMIN", l: "管理员" },
  { v: "OPERATOR", l: "运维" },
  { v: "VIEWER", l: "只读" },
];

const baseline = ref<Baseline>({ bindLocalOnly: true, authRequired: true, sessionTimeoutMinutes: 30, blockedCommands: [], auditEnabled: true });
const users = ref<PlatformUser[]>([]);
const usersLoading = ref(true);
const createOpen = ref(false);
const creating = ref(false);
const form = reactive({ username: "", displayName: "", password: "", role: "VIEWER" });

async function loadUsers() {
  usersLoading.value = true;
  try {
    users.value = await http.get("/admin/users") as unknown as PlatformUser[];
  } catch (e) {
    users.value = [];
    ElMessage.error("用户列表加载失败：" + errMsg(e));
  } finally {
    usersLoading.value = false;
  }
}

async function createUser() {
  creating.value = true;
  try {
    await http.post("/admin/users", { ...form });
    createOpen.value = false;
    form.username = "";
    form.displayName = "";
    form.password = "";
    form.role = "VIEWER";
    ElMessage.success("用户已创建");
    await loadUsers();
  } catch (e) {
    ElMessage.error(errMsg(e));
  } finally {
    creating.value = false;
  }
}

async function resetPwd(row: PlatformUser) {
  try {
    const { value } = await ElMessageBox.prompt(`为账号「${row.username}」设置新密码（至少 6 位）`, "重置密码", {
      inputType: "password", inputPattern: /^.{6,}$/, inputErrorMessage: "密码至少 6 位",
    });
    await http.post(`/admin/users/${row.username}/password`, { password: value });
    ElMessage.success("密码已重置");
  } catch (e) {
    if (e !== "cancel" && e !== "close") ElMessage.error(errMsg(e));
  }
}

async function toggleEnabled(row: PlatformUser) {
  try {
    await http.post(`/admin/users/${row.username}/enabled`, { enabled: !row.enabled });
    ElMessage.success(row.enabled ? `已停用 ${row.username}` : `已启用 ${row.username}`);
    await loadUsers();
  } catch (e) {
    ElMessage.error(errMsg(e));
  }
}

async function rename(row: PlatformUser) {
  try {
    const { value } = await ElMessageBox.prompt(`修改账号「${row.username}」的显示名`, "改名", {
      inputValue: row.displayName,
      inputPattern: /^\S.{0,31}$/,
      inputErrorMessage: "显示名需 1–32 个字符",
      confirmButtonText: "保存",
      cancelButtonText: "取消",
    });
    await http.post(`/admin/users/${row.username}/display-name`, { displayName: value });
    if (row.username === auth.user?.username) auth.setDisplayName(value.trim());
    ElMessage.success("显示名已更新");
    await loadUsers();
  } catch (e) {
    if (e !== "cancel" && e !== "close") ElMessage.error(errMsg(e));
  }
}

async function removeUser(row: PlatformUser) {
  try {
    await ElMessageBox.confirm(`确定删除账号「${row.username}」？删除后无法恢复（其登录态立即失效）。`, "删除用户", {
      type: "warning", confirmButtonText: "删除", cancelButtonText: "取消",
    });
    await http.delete(`/admin/users/${row.username}`);
    ElMessage.success(`已删除 ${row.username}`);
    await loadUsers();
  } catch (e) {
    if (e !== "cancel" && e !== "close") ElMessage.error(errMsg(e));
  }
}

async function changeRole(row: PlatformUser, role: string) {
  try {
    await http.post(`/admin/users/${row.username}/role`, { role });
    ElMessage.success(`${row.username} 角色已调整为 ${role}`);
    await loadUsers();
  } catch (e) {
    ElMessage.error(errMsg(e));
    await loadUsers();
  }
}

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
  try {
    baseline.value = await http.get("/baseline") as unknown as Baseline;
  } catch (e) {
    ElMessage.error("安全基线加载失败：" + errMsg(e));
  }
  loadUsers();
});
</script>

<style scoped>
.card-hd { display: flex; align-items: center; justify-content: space-between; }
/* 文本列不换行；权限点列（wrap-cell）保持标签流式换行 */
.perm-table :deep(.el-table__cell .cell),
.users-table :deep(.el-table__cell .cell) { white-space: nowrap; }
.users-table :deep(.el-table__cell.wrap-cell .cell) { white-space: normal; }
/* 安全基线标签列不换行（高危黑名单等 5 字标签） */
:deep(.el-descriptions__label .cell) { white-space: nowrap; }
</style>
