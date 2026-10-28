<template>
  <div>
    <div class="row">
      <el-select v-model="source" placeholder="全部来源" clearable style="width: 140px" @change="resetFilters">
        <el-option v-for="s in ['人工', '急诊', '系统']" :key="s" :label="s" :value="s" />
      </el-select>
      <el-input v-model="operator" placeholder="按操作人过滤…" style="width: 200px" clearable @input="debouncedLoad" />
      <el-button size="small" type="warning" plain :loading="verifying" @click="verify">校验哈希链</el-button>
      <span class="sub">共 {{ stats.inDb ?? 0 }} 条 · 追加写 · 防篡改</span>
    </div>
    <el-alert
      v-if="verifyResult"
      :type="verifyResult.valid ? 'success' : 'error'"
      :closable="true"
      style="margin-top: 10px"
      :title="verifyResult.valid
        ? `完整性校验通过：已核验 ${verifyResult.checked} 条记录，链路连续无篡改`
        : `链路断裂于第 ${verifyResult.brokenAtSeq} 条：${verifyResult.reason}（已核验前 ${verifyResult.checked} 条）`" />
    <el-card shadow="never" style="margin-top: 12px">
      <el-table :data="rows" size="small" v-loading="loading">
        <el-table-column prop="seq" label="#" width="60" />
        <el-table-column label="时间" width="100">
          <template #default="{ row }">{{ hhmmss(row.time) }}</template>
        </el-table-column>
        <el-table-column prop="source" label="来源" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="row.source === '急诊' ? 'danger' : (row.source === '系统' ? 'info' : 'primary')" effect="plain">
              {{ row.source }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="operator" label="操作人" width="110" />
        <el-table-column prop="instanceId" label="实例" min-width="120" />
        <el-table-column prop="command" label="命令 / 动作" min-width="260">
          <template #default="{ row }"><code class="cmd">{{ row.command }}</code></template>
        </el-table-column>
        <el-table-column prop="costMs" label="耗时(ms)" width="90" align="right" />
        <el-table-column prop="result" label="结果" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="row.result === 'ok' ? 'success' : (row.result === 'blocked' ? 'danger' : 'warning')" effect="plain">
              {{ row.result === "ok" ? "成功" : row.result === "blocked" ? "已拦截" : "失败" }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="哈希" width="110">
          <template #default="{ row }">
            <el-tooltip :content="`本条 hash: ${row.hash}\n上一条 prev: ${row.prevHash}`" placement="top">
              <code class="hash">{{ (row.hash || "").slice(0, 8) }}</code>
            </el-tooltip>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination
        v-model:current-page="page"
        v-model:page-size="size"
        class="pager"
        :page-sizes="[20, 50, 100, 200]"
        :total="total"
        :disabled="loading"
        layout="total, sizes, prev, pager, next, jumper"
        @current-change="load"
        @size-change="onSizeChange" />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from "vue";
import { ElMessage } from "element-plus";
import { errMsg, http } from "@/api/http";

interface AuditEntry { seq: number; time: string; source: string; operator: string; instanceId: string; command: string; costMs: number; result: string; prevHash: string; hash: string }
interface VerifyResult { valid: boolean; checked: number; brokenAtSeq: number; reason: string }

const rows = ref<AuditEntry[]>([]);
const source = ref("");
const operator = ref("");
const stats = ref<Record<string, number>>({});
const total = ref(0);
const page = ref(1);
const size = ref(20);
const loading = ref(true);
const verifying = ref(false);
const verifyResult = ref<VerifyResult | null>(null);
let timer: number | undefined;

async function load() {
  loading.value = true;
  try {
    const params: Record<string, string | number> = { page: page.value - 1, size: size.value };
    if (source.value) params.source = source.value;
    if (operator.value) params.operator = operator.value;
    const data = await http.get("/audit", { params }) as unknown as { total: number; records: AuditEntry[] };
    rows.value = data.records ?? [];
    total.value = data.total ?? 0;
    stats.value = await http.get("/audit/stats") as unknown as Record<string, number>;
  } catch (e) {
    ElMessage.error("审计记录加载失败：" + errMsg(e));
  } finally {
    loading.value = false;
  }
}

/** 切换每页条数后回到第一页，避免页码越界 */
function onSizeChange() {
  page.value = 1;
  load();
}

async function verify() {
  verifying.value = true;
  try {
    verifyResult.value = await http.get("/audit/verify") as unknown as VerifyResult;
  } finally {
    verifying.value = false;
  }
}

/** 过滤条件变化时回到第一页再查，避免停留在越界页码 */
function resetFilters() {
  page.value = 1;
  load();
}

function debouncedLoad() {
  window.clearTimeout(timer);
  timer = window.setTimeout(resetFilters, 250);
}

function hhmmss(t: string) {
  const d = new Date(t);
  return `${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}:${String(d.getSeconds()).padStart(2, "0")}`;
}

onMounted(load);
</script>

<style scoped>
.row { display: flex; align-items: center; gap: 10px; }
.sub { font-size: 12px; color: #909399; margin-left: auto; }
.pager { margin-top: 12px; justify-content: flex-end; }
.cmd { font-size: 12px; overflow-wrap: anywhere; }
.hash { font-size: 12px; color: #b88230; }
</style>
