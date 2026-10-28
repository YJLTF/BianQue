<template>
  <el-row :gutter="12">
    <el-col :span="24" class="col-alerts">
      <el-card shadow="never" header="活动告警（Webhook 接入：Prometheus / Grafana / Zabbix）">
        <el-table :data="alerts" size="small" v-loading="loading" class="alerts-table">
          <el-table-column label="时间" width="54">
            <template #default="{ row }">{{ hhmm(row.time) }}</template>
          </el-table-column>
          <el-table-column label="应用 / 实例" min-width="120" show-overflow-tooltip>
            <template #default="{ row }"><code>{{ row.appId }}/{{ row.instanceId }}</code></template>
          </el-table-column>
          <el-table-column prop="message" label="告警" min-width="126" show-overflow-tooltip />
          <el-table-column prop="severity" label="级别" width="64">
            <template #default="{ row }">
              <el-tag size="small" :type="row.severity === '严重' ? 'danger' : 'warning'" effect="plain">
                {{ row.severity }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="来源" width="74">
            <template #default="{ row }">
              <el-tag size="small" :type="row.alertId.startsWith('AL-1') ? 'info' : 'success'" effect="plain">
                {{ row.alertId.startsWith("AL-1") ? "演示" : "已接入" }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="84">
            <template #default="{ row }">
              <el-button size="small" type="primary" :loading="busy === row.alertId"
                         :disabled="row.status === '已取证'" @click="rescue(row.alertId)">取证</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-card>
      <el-card shadow="never" header="告警接入（自有告警系统 · 通用接口，不绑定 IM）" style="margin-top: 12px">
        <pre class="out"># 入站：自有告警系统推入（服务端配置 bianque.alerts.ingest-token 后开放）
curl -X POST http://&lt;平台&gt;:8080/api/alerts/ingest \
  -H "X-Alert-Token: &lt;ingest-token&gt;" -H "Content-Type: application/json" \
  -d '{"alertId":"EXT-001","appId":"order-app","instanceId":"pod-3",
       "message":"CPU 96%","severity":"严重","source":"my-alert-center"}'

# 出站：新告警/取证完成时平台 POST JSON 到 bianque.alerts.webhook-url
# 报文：{event, alertId, appId, instanceId, severity, message, status, time[, conclusion]}</pre>
      </el-card>
    </el-col>
    <el-col :span="24" class="col-report">
      <el-card shadow="never" header="现场报告">
        <template v-if="report">
          <el-alert type="warning" :closable="false" :title="report.conclusion" show-icon />
          <h4 class="h">取证步骤</h4>
          <el-steps :active="report.steps.length" align-center finish-status="success" simple>
            <el-step v-for="s in report.steps" :key="s.order" :title="s.name" />
          </el-steps>
          <h4 class="h">内存水位 / 线程</h4>
          <el-descriptions :column="3" size="small" border>
            <el-descriptions-item label="oldGen">{{ report.memory.oldGen }}%</el-descriptions-item>
            <el-descriptions-item label="线程数">{{ report.memory.threads }}</el-descriptions-item>
            <el-descriptions-item label="耗时">{{ report.costMs }}ms</el-descriptions-item>
          </el-descriptions>
          <h4 class="h">线程 TOP</h4>
          <pre class="out">{{ report.threadTop.join("\n") }}</pre>
        </template>
        <el-empty v-else description="点击「取证」：attach → thread → memory → jvm → 生成现场报告" :image-size="60" />
      </el-card>
    </el-col>
  </el-row>
</template>

<script setup lang="ts">
import { onMounted, ref } from "vue";
import { ElMessage } from "element-plus";
import { http, errMsg } from "@/api/http";

interface Alert { alertId: string; time: string; appId: string; instanceId: string; message: string; severity: string; status: string }
interface RescueReport {
  alertId: string; instanceId: string; conclusion: string; costMs: number;
  steps: { order: number; name: string; command: string; status: string; outputHead: string }[];
  threadTop: string[]; memory: Record<string, number>; generatedAt: string;
}

const alerts = ref<Alert[]>([]);
const report = ref<RescueReport | null>(null);
const loading = ref(true);
const busy = ref("");

function hhmm(t: string) {
  const d = new Date(t);
  return `${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`;
}

async function rescue(alertId: string) {
  busy.value = alertId;
  try {
    report.value = await http.post(`/alerts/${alertId}/rescue`) as unknown as RescueReport;
    alerts.value = await http.get("/alerts") as unknown as Alert[];
    ElMessage.success("现场报告已生成并推送值班群（模拟）");
  } catch (e) {
    ElMessage.error(errMsg(e));
  } finally {
    busy.value = "";
  }
}

onMounted(async () => {
  alerts.value = await http.get("/alerts") as unknown as Alert[];
  loading.value = false;
});
</script>

<style scoped>
.h { margin: 12px 0 6px; font-size: 13px; }
.alerts-table :deep(.cell) { padding: 0 8px; }
/* 6 列表格至少需要 ~520px：视口 ≥1560px 才左右分栏，否则上下堆叠 */
@media (min-width: 1560px) {
  .col-alerts { flex: 0 0 45.833%; max-width: 45.833%; }
  .col-report { flex: 0 0 54.167%; max-width: 54.167%; }
}
@media (max-width: 1559px) {
  .col-report { margin-top: 12px; }
}
</style>
