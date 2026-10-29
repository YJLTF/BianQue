<template>
  <div v-loading="loading">
    <el-row :gutter="12">
      <el-col v-for="card in cards" :key="card.label" :span="6">
        <el-card shadow="never" class="stat">
          <div class="stat-top">
            <span class="tile" :class="card.tone"><el-icon><component :is="card.icon" /></el-icon></span>
            <span class="k">{{ card.label }}</span>
          </div>
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
            <el-tag :type="tagType(row.status)" effect="plain" round>{{ row.status }}</el-tag>
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
interface Channels { real: number; mock: number }
interface Overview { appCount: number; instanceCount: number; onlineCount: number; avgHealth: number; auditCount: number; channels: Channels; apps: AppSummary[] }

const ov = ref<Overview | null>(null);
const loading = ref(true);

const cards = computed(() => {
  const o = ov.value;
  const offline = o ? o.instanceCount - o.onlineCount : 0;
  return [
    { label: "应用 / 实例", value: o ? `${o.appCount} / ${o.instanceCount}` : "-", desc: `${offline} 台离线`, icon: "Histogram", tone: "indigo" },
    { label: "平均健康分", value: o ? o.avgHealth.toFixed(1) : "-", desc: "来自最近一次巡检", icon: "Odometer", tone: "jade" },
    { label: "审计事件", value: String(o?.auditCount ?? "-"), desc: "操作留痕 · 防篡改", icon: "Memo", tone: "amber" },
    { label: "执行通道", value: o ? `真实 ${o.channels.real} · 拟真 ${o.channels.mock}` : "-",
      desc: "按实例自动路由", icon: "Connection", tone: "violet" },
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
.tile.violet { background: linear-gradient(135deg, #7c3aed, #a855f7); }
.k { font-size: 13px; color: var(--el-text-color-secondary); }
.v { font-size: 26px; font-weight: 700; color: var(--el-text-color-primary); margin-top: 6px; }
.d { font-size: 12px; color: var(--el-text-color-secondary); margin-top: 2px; }
</style>
