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
            <el-tag v-for="c in baseline.blockedCommands ?? []" :key="c" size="small" type="danger" effect="plain"
                    title="内置基线（安全红线，恒拦截不可移除）" style="margin: 0 4px 4px 0">
              {{ c }}
            </el-tag>
            <el-tag v-for="c in baseline.blockedExtraCommands ?? []" :key="'x-' + c" size="small" type="warning"
                    effect="plain" title="管理员追加项（可在下方高危命令黑名单卡移除）" style="margin: 0 4px 4px 0">
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
        <span>参数配置</span>
        <el-button size="small" text @click="loadConfigs">刷新</el-button>
      </div>
    </template>
    <el-alert type="info" :closable="false" style="margin-bottom: 10px"
              title="改动即时生效并入审计；端口绑定 / 强制认证 / 审计开关为安全红线不可配；高危黑名单内置基线不可削减，追加项在下方「高危命令黑名单」管理" />
    <el-table :data="configs" size="small" v-loading="configsLoading" class="cfg-table">
      <el-table-column prop="category" label="分类" width="90" />
      <el-table-column label="参数" min-width="260">
        <template #default="{ row }">
          <div class="cfg-name">{{ row.displayName }}<code class="cfg-key">{{ row.key }}</code></div>
          <div class="cfg-desc">{{ row.description }}</div>
        </template>
      </el-table-column>
      <el-table-column label="当前值" width="200">
        <template #default="{ row }">
          <div class="cfg-val">
            <el-input-number v-model="drafts[row.key]" :min="row.min" :max="row.max" :step="1"
                             size="small" controls-position="right" style="width: 130px" />
            <el-tag v-if="row.customized" size="small" type="warning" effect="plain">已改</el-tag>
          </div>
        </template>
      </el-table-column>
      <el-table-column prop="defaultValue" label="默认" width="60" />
      <el-table-column prop="effect" label="生效时机" min-width="150" />
      <el-table-column label="最近修改" width="170">
        <template #default="{ row }">
          <span v-if="row.updatedBy" class="cfg-desc">{{ row.updatedBy }}<br>{{ fmtTime(row.updatedAt) }}</span>
          <span v-else class="cfg-desc">默认值</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="150">
        <template #default="{ row }">
          <el-button size="small" type="primary" text :disabled="String(drafts[row.key]) === row.value"
                     @click="saveConfig(row)">保存</el-button>
          <el-button size="small" text :disabled="!row.customized" @click="resetConfigRow(row)">恢复默认</el-button>
        </template>
      </el-table-column>
    </el-table>
  </el-card>

  <el-card shadow="never" style="margin-top: 12px">
    <template #header>
      <div class="card-hd">
        <span>高危命令黑名单</span>
        <el-button size="small" text @click="reloadBaseline(); loadConfigs()">刷新</el-button>
      </div>
    </template>
    <el-alert type="warning" :closable="false" style="margin-bottom: 10px"
              title="内置基线为安全红线：恒拦截、不可移除、不可放行；追加项只能加严，即时生效（人工诊断 / AI 会诊 / 模板保存统一拦截），所有改动入审计" />
    <div class="bl-row">
      <span class="bl-label">内置基线</span>
      <el-tag v-for="c in baseline.blockedCommands ?? []" :key="'b-' + c" size="small" type="danger" effect="dark"
              class="bl-tag" title="内置高危命令，恒拦截不可移除">{{ c }}</el-tag>
    </div>
    <div class="bl-row">
      <span class="bl-label">追加项</span>
      <template v-if="(baseline.blockedExtraCommands ?? []).length">
        <el-tag v-for="c in baseline.blockedExtraCommands ?? []" :key="'e-' + c" size="small" type="warning"
                class="bl-tag" :closable="canManage" title="管理员追加项，× 移除后该命令恢复放行"
                @close="removeBlocked(c)">{{ c }}</el-tag>
      </template>
      <span v-else class="cfg-desc">暂无追加（仅内置基线生效）</span>
    </div>
    <div v-if="canManage" class="bl-row bl-actions">
      <el-input v-model="blInput" size="small" style="width: 280px" clearable
                placeholder="命令名，逗号/空格分隔可批量，如 jad sc" @keyup.enter="addBlocked" />
      <el-button size="small" type="danger" plain :disabled="!blInput.trim()" @click="addBlocked">追加拦截</el-button>
      <el-button size="small" text :disabled="!(baseline.blockedExtraCommands ?? []).length" @click="resetBlocked">
        恢复默认（清空追加项）
      </el-button>
      <span v-if="blockedMeta" class="cfg-desc">
        最近修改：{{ blockedMeta.updatedBy || "默认值" }}<template v-if="blockedMeta.updatedAt"> · {{ fmtTime(blockedMeta.updatedAt) }}</template>
      </span>
    </div>
  </el-card>

  <el-card shadow="never" style="margin-top: 12px">
    <template #header>
      <div class="card-hd">
        <span>诊断模板</span>
        <span>
          <el-button size="small" text @click="loadTpls">刷新</el-button>
          <el-button v-if="canManage" size="small" type="primary" plain @click="openTplDialog()">新建模板</el-button>
        </span>
      </div>
    </template>
    <el-alert type="info" :closable="false" style="margin-bottom: 10px"
              title="诊断台场景 / 巡检体检命令集 / 急诊取证序列统一管理；内置模板可停用可复制不可删除；命令支持 {{占位符}}，应用模板时填参" />
    <el-table :data="tplsAll" size="small" v-loading="tplsLoading" class="cfg-table">
      <el-table-column prop="name" label="名称" width="180" />
      <el-table-column label="类型" width="90">
        <template #default="{ row }">
          <el-tag size="small" effect="plain" :type="row.kind === 'diag' ? 'primary' : row.kind === 'checkup' ? 'success' : 'danger'">
            {{ row.kind === "diag" ? "诊断台" : row.kind === "checkup" ? "体检" : "急诊" }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="scene" label="场景" width="100" />
      <el-table-column label="命令序列" min-width="320" class-name="wrap-cell">
        <template #default="{ row }">
          <code v-for="(it, i) in row.items" :key="i" class="tpl-cmd">{{ it.command }}</code>
        </template>
      </el-table-column>
      <el-table-column label="占位符" width="130">
        <template #default="{ row }">
          <el-tag v-for="p in row.placeholders" :key="p" size="small" effect="plain" style="margin: 0 4px 4px 0">{{ p }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="内置" width="70">
        <template #default="{ row }">
          <el-tag v-if="row.builtIn" size="small" type="warning" effect="plain">内置</el-tag>
          <span v-else class="tpl-sub">{{ row.createdBy }}</span>
        </template>
      </el-table-column>
      <el-table-column label="启用" width="80">
        <template #default="{ row }">
          <el-switch :model-value="row.enabled" @change="(v: boolean) => toggleTpl(row, v)" />
        </template>
      </el-table-column>
      <el-table-column label="操作" width="140">
        <template #default="{ row }">
          <el-button v-if="canManage" size="small" text type="primary" @click="openTplDialog(row)">编辑</el-button>
          <el-button v-if="canManage" size="small" text type="danger" :disabled="row.builtIn"
                     :title="row.builtIn ? '内置模板不可删除' : ''" @click="delTpl(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
  </el-card>

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

  <el-dialog v-model="tplDialog" :title="tplEditing ? '编辑模板' : '新建模板'" width="600px">
    <el-form label-width="90px" size="small">
      <el-form-item label="名称">
        <el-input v-model="tplForm.name" placeholder="如：连接池水位" />
      </el-form-item>
      <el-form-item label="类型">
        <el-select v-model="tplForm.kind" style="width: 220px">
          <el-option value="diag" label="诊断台场景" />
          <el-option value="checkup" label="巡检体检命令集" />
          <el-option value="rescue" label="急诊取证序列" />
        </el-select>
      </el-form-item>
      <el-form-item label="场景分类">
        <el-input v-model="tplForm.scene" placeholder="如：CPU / 内存 / 自定义" style="width: 220px" />
      </el-form-item>
      <el-form-item label="命令序列">
        <div style="width: 100%">
          <div v-for="(it, i) in tplForm.items" :key="i" class="tpl-row">
            <el-input v-model="it.name" placeholder="步骤名（急诊报告显示，默认同命令）" style="width: 180px" />
            <el-input v-model="it.command" placeholder="arthas 命令，可含 {{占位符}}（应用模板时填参）" />
            <el-button text type="danger" :disabled="tplForm.items.length === 1"
                       @click="tplForm.items.splice(i, 1)">删</el-button>
          </div>
          <el-button size="small" text type="primary" @click="tplForm.items.push({ name: '', command: '' })">
            + 加一条命令
          </el-button>
        </div>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="tplDialog = false">取消</el-button>
      <el-button type="primary" :disabled="!tplForm.name.trim() || tplForm.items.some(it => !it.command.trim())"
                 @click="saveTpl">保存</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { http, errMsg } from "@/api/http";
import { getConfigs, resetConfig, updateConfig, type SysConfigItem } from "@/api/config";
import { deleteTemplate, enableTemplate, createTemplate, updateTemplate, getAllTemplates,
         type TplVO, type TplItem, type TplPayload } from "@/api/templates";
import { useAuthStore } from "@/stores/auth";

const auth = useAuthStore();
const canManage = computed(() => auth.can("admin:user"));

interface Baseline {
  bindLocalOnly: boolean;
  authRequired: boolean;
  sessionTimeoutMinutes: number;
  blockedCommands: string[];
  blockedExtraCommands?: string[];
  auditEnabled: boolean;
}
interface PlatformUser { username: string; displayName: string; enabled: boolean; roles: string[]; permissions: string[] }

const ROLES = [
  { v: "ADMIN", l: "管理员" },
  { v: "OPERATOR", l: "运维" },
  { v: "VIEWER", l: "只读" },
];

const baseline = ref<Baseline>({ bindLocalOnly: true, authRequired: true, sessionTimeoutMinutes: 30,
  blockedCommands: [], blockedExtraCommands: [], auditEnabled: true });
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

// ---- 参数中心 ----
const configs = ref<SysConfigItem[]>([]);
const configsAll = ref<SysConfigItem[]>([]);
const configsLoading = ref(false);
const drafts = reactive<Record<string, number>>({});

async function loadConfigs() {
  if (!canManage.value) return;
  configsLoading.value = true;
  try {
    configsAll.value = await getConfigs();
    // list 型（高危黑名单追加项）走独立管理卡，不进通用数字参数表
    configs.value = configsAll.value.filter((c) => c.valueType !== "list");
    for (const c of configs.value) drafts[c.key] = Number(c.value);
  } catch (e) {
    configs.value = [];
    ElMessage.error("参数加载失败：" + errMsg(e));
  } finally {
    configsLoading.value = false;
  }
}

async function saveConfig(row: SysConfigItem) {
  try {
    const updated = await updateConfig(row.key, String(drafts[row.key]));
    const idx = configs.value.findIndex((c) => c.key === row.key);
    if (idx >= 0) configs.value[idx] = updated;
    drafts[row.key] = Number(updated.value);
    ElMessage.success(`已保存：${row.displayName} = ${updated.value}（${updated.effect}）`);
  } catch (e) {
    ElMessage.error(errMsg(e));
  }
}

async function resetConfigRow(row: SysConfigItem) {
  try {
    const updated = await resetConfig(row.key);
    const idx = configs.value.findIndex((c) => c.key === row.key);
    if (idx >= 0) configs.value[idx] = updated;
    drafts[row.key] = Number(updated.value);
    ElMessage.success(`已恢复默认：${row.displayName} = ${updated.defaultValue}`);
  } catch (e) {
    ElMessage.error(errMsg(e));
  }
}

function fmtTime(t?: string) {
  if (!t) return "";
  const d = new Date(t);
  const p = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}

// ---- 高危命令黑名单（参数中心 list 型项，仅可加严）----
const BLACKLIST_KEY = "diag.blocked-extra-commands";
const blInput = ref("");
const blockedMeta = computed(() => configsAll.value.find((c) => c.key === BLACKLIST_KEY) ?? null);

async function reloadBaseline() {
  try {
    baseline.value = await http.get("/baseline") as unknown as Baseline;
  } catch (e) {
    ElMessage.error("安全基线加载失败：" + errMsg(e));
  }
}

/** 写追加项并同步基线/参数元数据（value 为逗号并集串，空串 = 清空） */
async function saveBlocked(value: string, okMsg: string) {
  try {
    await updateConfig(BLACKLIST_KEY, value);
    ElMessage.success(okMsg);
    await reloadBaseline();
    loadConfigs();
  } catch (e) {
    ElMessage.error(errMsg(e));
  }
}

async function addBlocked() {
  const tokens = blInput.value.split(/[,，;；\s]+/).map(s => s.trim().toLowerCase()).filter(Boolean);
  if (!tokens.length) return;
  const bad = tokens.find(t => !/^[a-z][a-z0-9-]*$/.test(t));
  if (bad) {
    ElMessage.warning(`命令名非法：${bad}（须为小写字母开头的命令 token）`);
    return;
  }
  const dupBase = tokens.find(t => (baseline.value.blockedCommands ?? []).includes(t));
  if (dupBase) {
    ElMessage.warning(`${dupBase} 已在内置基线中恒拦截，无需追加`);
    return;
  }
  const merged = [...new Set([...(baseline.value.blockedExtraCommands ?? []), ...tokens])].join(",");
  const added = tokens.filter(t => !(baseline.value.blockedExtraCommands ?? []).includes(t)).join(", ");
  await saveBlocked(merged, `已追加拦截：${added}（即时生效并入审计）`);
  blInput.value = "";
}

async function removeBlocked(cmd: string) {
  const merged = (baseline.value.blockedExtraCommands ?? []).filter(c => c !== cmd).join(",");
  await saveBlocked(merged, `已移除追加项：${cmd}（该命令恢复放行）`);
}

async function resetBlocked() {
  await saveBlocked("", "已清空全部追加项（仅内置基线生效）");
}

// ---- 诊断模板 ----
const tplsAll = ref<TplVO[]>([]);
const tplsLoading = ref(false);
const tplDialog = ref(false);
const tplEditing = ref<TplVO | null>(null);
const tplForm = reactive<{ name: string; kind: string; scene: string; items: TplItem[] }>({
  name: "", kind: "diag", scene: "自定义", items: [{ name: "", command: "" }],
});

async function loadTpls() {
  tplsLoading.value = true;
  try {
    tplsAll.value = await getAllTemplates();
  } catch (e) {
    tplsAll.value = [];
    ElMessage.error("模板加载失败：" + errMsg(e));
  } finally {
    tplsLoading.value = false;
  }
}

function openTplDialog(row?: TplVO) {
  tplEditing.value = row ?? null;
  tplForm.name = row?.name ?? "";
  tplForm.kind = row?.kind ?? "diag";
  tplForm.scene = row?.scene ?? "自定义";
  tplForm.items = row ? row.items.map(it => ({ ...it })) : [{ name: "", command: "" }];
  tplDialog.value = true;
}

async function saveTpl() {
  if (!tplForm.name.trim() || tplForm.items.some(it => !it.command.trim())) return;
  const payload: TplPayload = {
    name: tplForm.name.trim(), kind: tplForm.kind, scene: tplForm.scene.trim() || "自定义",
    items: tplForm.items.map(it => ({ name: (it.name || it.command).trim(), command: it.command.trim() })),
  };
  try {
    if (tplEditing.value) await updateTemplate(tplEditing.value.id, payload);
    else await createTemplate(payload);
    ElMessage.success(tplEditing.value ? "模板已更新" : "模板已创建");
    tplDialog.value = false;
    await loadTpls();
  } catch (e) {
    ElMessage.error(errMsg(e));
  }
}

async function toggleTpl(row: TplVO, enabled: boolean) {
  try {
    await enableTemplate(row.id, enabled);
    row.enabled = enabled;
    ElMessage.success(`${enabled ? "已启用" : "已停用"}「${row.name}」`);
  } catch (e) {
    ElMessage.error(errMsg(e));
  }
}

async function delTpl(row: TplVO) {
  try {
    await ElMessageBox.confirm(`确定删除模板「${row.name}」？`, "删除模板", {
      type: "warning", confirmButtonText: "删除", cancelButtonText: "取消",
    });
    await deleteTemplate(row.id);
    ElMessage.success(`已删除「${row.name}」`);
    await loadTpls();
  } catch (e) {
    if (e !== "cancel" && e !== "close") ElMessage.error(errMsg(e));
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
  reloadBaseline();
  loadUsers();
  loadConfigs();
  loadTpls();
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
/* 参数配置：名称/key 两行 + 描述灰字；当前值行内数字框与「已改」标记 */
.cfg-name { font-weight: 500; }
.cfg-key { margin-left: 8px; font-size: 11px; color: var(--el-text-color-secondary); font-weight: 400; }
.cfg-desc { font-size: 11.5px; color: var(--el-text-color-secondary); line-height: 1.5; }
.cfg-val { display: flex; align-items: center; gap: 6px; }
.cfg-table :deep(.el-table__cell .cell) { white-space: nowrap; }
.cfg-table :deep(.el-table__cell) { padding: 7px 0; }
/* 诊断模板：命令序列 chip、编辑行、非内置创建人 */
.tpl-cmd {
  display: inline-block; margin: 0 6px 4px 0; padding: 2px 8px; font-size: 11.5px;
  background: #f6f8fa; border: 1px solid #eef1f6; border-radius: 4px;
}
.tpl-sub { font-size: 11.5px; color: var(--el-text-color-secondary); }
.tpl-row { display: flex; gap: 6px; margin-bottom: 6px; width: 100%; }
/* 高危命令黑名单：基线/追加两行 chips + 操作行 */
.bl-row { display: flex; align-items: center; flex-wrap: wrap; gap: 6px; margin: 4px 0 10px; }
.bl-label { width: 64px; font-size: 12.5px; color: var(--el-text-color-regular); flex-shrink: 0; }
.bl-tag { margin: 0; }
.bl-actions { margin-bottom: 0; }
</style>
