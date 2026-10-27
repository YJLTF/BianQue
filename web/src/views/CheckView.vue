<template>
  <el-row :gutter="12">
    <el-col :span="14">
      <el-card shadow="never">
        <template #header>
          <div class="row"><b>巡检体检</b>
            <span v-if="avgScore" class="avg">平均健康分 <b>{{ avgScore }}</b> / {{ instanceCount }} 实例</span>
            <el-button size="small" type="primary" :loading="running" style="margin-left: auto" @click="run">
              立即巡检（在线实例）
            </el-button>
          </div>
        </template>
        <el-table :data="rows" size="small" v-loading="running">
          <el-table-column prop="instanceId" label="实例" min-width="120" />
          <el-table-column prop="score" label="健康分" width="100">
            <template #default="{ row }">
              <el-tag size="small" :type="row.score >= 90 ? 'success' : (row.score >= 80 ? 'warning' : 'danger')" effect="plain">
                {{ row.score }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="扣分项" min-width="220">
            <template #default="{ row }">
              <el-tag v-for="d in row.deductions" :key="d" size="small" type="danger" effect="plain" style="margin-right: 6px">
                {{ d }}
              </el-tag>
              <span v-if="!row.deductions?.length" class="sub">—</span>
            </template>
          </el-table-column>
        </el-table>
      </el-card>
    </el-col>
    <el-col :span="10">
      <el-alert type="info" :closable="false" title="巡检模板：jvm / memory / thread（只读命令集）· 阈值打分 · 结果落库"
                description="S1 起每次巡检写入数据库，重启不丢；S2 规划：每日 02:00 错峰调度、趋势周报、企微推送" />
      <el-card shadow="never" style="margin-top: 12px">
        <template #header><b>运行历史（最近 20 次）</b></template>
        <el-table :data="history" size="small" v-loading="historyLoading" max-height="360">
          <el-table-column label="时间" width="160">
            <template #default="{ row }">{{ fmt(row.runAt) }}</template>
          </el-table-column>
          <el-table-column prop="instanceCount" label="实例数" width="80" align="center" />
          <el-table-column prop="avgScore" label="平均分" width="80" align="center" />
          <el-table-column prop="operator" label="触发人" min-width="100" />
        </el-table>
      </el-card>
    </el-col>
  </el-row>
</template>

<script setup lang="ts">
import { onMounted, ref } from "vue";
import { ElMessage } from "element-plus";
import { http, errMsg } from "@/api/http";

interface CheckupRow { instanceId: string; score: number; deductions: string[] }
interface CheckupSummary { rows: CheckupRow[]; avgScore: number; instanceCount: number }
interface RunHistory { runAt: string; operator: string; instanceCount: number; avgScore: number }

const rows = ref<CheckupRow[]>([]);
const avgScore = ref(0);
const instanceCount = ref(0);
const history = ref<RunHistory[]>([]);
const running = ref(false);
const historyLoading = ref(true);

async function run() {
  running.value = true;
  try {
    // 巡检含真实通道实例（agent 冷 attach 秒级），放宽单次超时
    const s = await http.post("/checkup/run", null, { timeout: 150000 }) as unknown as CheckupSummary;
    apply(s);
    loadHistory();
  } catch (e) {
    ElMessage.error(errMsg(e));
  } finally {
    running.value = false;
  }
}

function apply(s: CheckupSummary) {
  rows.value = s.rows ?? [];
  avgScore.value = s.avgScore ?? 0;
  instanceCount.value = s.instanceCount ?? 0;
}

async function loadHistory() {
  historyLoading.value = true;
  try {
    history.value = await http.get("/checkup/history") as unknown as RunHistory[];
  } finally {
    historyLoading.value = false;
  }
}

function fmt(t: string) {
  const d = new Date(t);
  return `${d.getMonth() + 1}-${String(d.getDate()).padStart(2, "0")} ${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`;
}

onMounted(async () => {
  apply(await http.get("/checkup/latest") as unknown as CheckupSummary);
  loadHistory();
});
</script>

<style scoped>
.row { display: flex; align-items: center; }
.sub { font-size: 12px; color: #909399; }
.avg { font-size: 12px; color: #606266; margin-left: 10px; }
</style>
