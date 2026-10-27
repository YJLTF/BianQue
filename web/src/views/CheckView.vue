<template>
  <el-row :gutter="12">
    <el-col :span="14">
      <el-card shadow="never">
        <template #header>
          <div class="row"><b>巡检体检</b>
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
      <el-alert type="info" :closable="false" title="巡检模板：jvm / memory / thread（只读命令集）· 阈值打分 · 连续劣化预警"
                description="S1 后续：每日 02:00 错峰调度、趋势周报、企微推送" />
    </el-col>
  </el-row>
</template>

<script setup lang="ts">
import { onMounted, ref } from "vue";
import { http } from "@/api/http";

interface CheckupRow { instanceId: string; score: number; deductions: string[] }

const rows = ref<CheckupRow[]>([]);
const running = ref(false);

async function run() {
  running.value = true;
  rows.value = await http.post("/checkup/run") as unknown as CheckupRow[];
  running.value = false;
}

onMounted(async () => {
  rows.value = await http.get("/checkup/latest") as unknown as CheckupRow[];
});
</script>

<style scoped>
.row { display: flex; align-items: center; }
.sub { font-size: 12px; color: #909399; }
</style>
