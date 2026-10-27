<template>
  <div>
    <div class="row">
      <el-select v-model="source" placeholder="全部来源" clearable style="width: 140px" @change="load">
        <el-option v-for="s in ['人工', '急诊', '系统']" :key="s" :label="s" :value="s" />
      </el-select>
      <el-input v-model="operator" placeholder="按操作人过滤…" style="width: 200px" clearable @input="debouncedLoad" />
      <span class="sub">累计写入 {{ stats.appended ?? 0 }} 条 · 追加写 · S1 升级哈希链防篡改</span>
    </div>
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
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from "vue";
import { http } from "@/api/http";

interface AuditEntry { seq: number; time: string; source: string; operator: string; instanceId: string; command: string; costMs: number; result: string }

const rows = ref<AuditEntry[]>([]);
const source = ref("");
const operator = ref("");
const stats = ref<Record<string, number>>({});
const loading = ref(true);
let timer: number | undefined;

async function load() {
  loading.value = true;
  const params: Record<string, string | number> = { limit: 100 };
  if (source.value) params.source = source.value;
  if (operator.value) params.operator = operator.value;
  rows.value = await http.get("/audit", { params }) as unknown as AuditEntry[];
  stats.value = await http.get("/audit/stats") as unknown as Record<string, number>;
  loading.value = false;
}

function debouncedLoad() {
  window.clearTimeout(timer);
  timer = window.setTimeout(load, 250);
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
.cmd { font-size: 12px; overflow-wrap: anywhere; }
</style>
