<template>
  <div v-loading="loading">
    <el-row :gutter="12">
      <el-col v-for="card in cards" :key="card.label" :span="6">
        <el-card shadow="never">
          <div class="k">{{ card.label }}</div>
          <div class="v">{{ card.value }}</div>
          <div class="d">{{ card.desc }}</div>
        </el-card>
      </el-col>
    </el-row>
    <el-card shadow="never" header="应用健康总览" style="margin-top: 12px">
      <el-table :data="ov?.apps ?? []" size="small">
        <el-table-column prop="name" label="应用" min-width="140">
          <template #default="{ row }"><code>{{ row.name }}</code></template>
        </el-table-column>
        <el-table-column prop="instanceCount" label="实例" width="80" align="right" />
        <el-table-column prop="healthScore" label="健康分" width="90" align="right" />
        <el-table-column prop="status" label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="tagType(row.status)" effect="plain">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="lastEvent" label="最近事件" min-width="180" />
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { http } from "@/api/http";

interface AppSummary { appId: string; name: string; instanceCount: number; onlineCount: number; healthScore: number; status: string; lastEvent: string }
interface Overview { appCount: number; instanceCount: number; onlineCount: number; avgHealth: number; auditCount: number; executionMode: string; apps: AppSummary[] }

const ov = ref<Overview | null>(null);
const loading = ref(true);

const cards = computed(() => {
  const o = ov.value;
  const offline = o ? o.instanceCount - o.onlineCount : 0;
  return [
    { label: "监护应用 / 实例", value: o ? `${o.appCount} / ${o.instanceCount}` : "-", desc: `tunnel 纳管 · ${offline} 台离线` },
    { label: "平均健康分", value: o ? o.avgHealth.toFixed(1) : "-", desc: "最近一次巡检" },
    { label: "审计事件", value: String(o?.auditCount ?? "-"), desc: "全操作留痕" },
    { label: "执行通道", value: o?.executionMode === "local" ? "as.sh 真实" : "拟真演示", desc: "bianque.execution.mode" },
  ];
});

async function load() {
  loading.value = true;
  ov.value = await http.get("/fleet/overview") as unknown as Overview;
  loading.value = false;
}
function tagType(status: string) {
  return status === "健康" ? "success" : status === "需关注" ? "warning" : "danger";
}
onMounted(load);
</script>

<style scoped>
.k { font-size: 12px; color: #909399; }
.v { font-size: 20px; font-weight: 600; margin-top: 2px; }
.d { font-size: 11px; color: #909399; margin-top: 2px; }
</style>
