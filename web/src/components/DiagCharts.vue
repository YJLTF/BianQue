<template>
  <div class="diag-charts">
    <div v-for="c in charts" :key="c.title" class="chart-cell">
      <div class="chart-title">{{ c.title }}</div>
      <div :ref="el => setRef(c.title, el)" class="chart-box" />
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted } from "vue";
import * as echarts from "echarts/core";
import { BarChart, LineChart, PieChart } from "echarts/charts";
import { GridComponent, TooltipComponent, LegendComponent } from "echarts/components";
import { CanvasRenderer } from "echarts/renderers";

echarts.use([BarChart, LineChart, PieChart, GridComponent, TooltipComponent, LegendComponent, CanvasRenderer]);

/** 诊断结果结构化指标（DiagResult.metrics，ResultParsers 产物） */
type MetricsVO = import("@/api/diag").DiagMetrics;

const props = defineProps<{ metrics: MetricsVO }>();

const instances = new Map<string, echarts.ECharts>();

function setRef(title: string, el: unknown) {
  if (!el) return;
  nextTick(() => {
    const host = el as HTMLElement;
    let chart = instances.get(title);
    if (!chart || chart.getDom() !== host) {
      chart?.dispose();
      chart = echarts.init(host);
      instances.set(title, chart);
    }
    chart.setOption(buildOption(title), true);
  });
}

const charts = computed(() => {
  const out: { title: string; }[] = [];
  const m = props.metrics;
  if (!m) return out;
  if (m.thread) {
    out.push({ title: "线程状态分布" });
    if (m.thread.topCpu?.length) out.push({ title: "CPU 占用 TOP" });
  }
  if (m.memory) out.push({ title: "内存池水位 (%)" });
  if (m.monitor?.length) out.push({ title: "接口观测趋势" });
  return out;
});

function buildOption(title: string): echarts.EChartsCoreOption {
  const m = props.metrics;
  if (title === "线程状态分布" && m.thread) {
    const data = Object.entries(m.thread.stateCounts ?? {}).map(([k, v]) => ({ name: k, value: v }));
    return {
      tooltip: { trigger: "item", formatter: "{b}: {c} ({d}%)" },
      legend: { bottom: 0, type: "scroll", textStyle: { fontSize: 11 } },
      series: [{
        type: "pie", radius: ["38%", "66%"], center: ["50%", "44%"],
        label: { fontSize: 11, formatter: "{b} {c}" },
        data: data.length ? data : [{ name: "未知", value: 1 }],
      }],
    };
  }
  if (title === "CPU 占用 TOP" && m.thread) {
    const rows = [...m.thread.topCpu].reverse();
    return {
      tooltip: { trigger: "axis", valueFormatter: (v: number) => v + "%" },
      grid: { left: 8, right: 30, top: 8, bottom: 8, containLabel: true },
      xAxis: { type: "value", axisLabel: { fontSize: 10, formatter: "{value}%" } },
      yAxis: { type: "category", data: rows.map(r => r.name), axisLabel: { fontSize: 10, width: 110, overflow: "truncate" } },
      series: [{ type: "bar", data: rows.map(r => r.cpuPct), itemStyle: { color: "#e6a23c" }, barMaxWidth: 14 }],
    };
  }
  if (title === "内存池水位 (%)" && m.memory) {
    const pools = (m.memory.pools ?? []).filter(p => p.usagePct >= 0);
    const rows = pools.slice(0, 10).reverse();
    return {
      tooltip: { trigger: "axis", valueFormatter: (v: number) => v.toFixed(1) + "%" },
      grid: { left: 8, right: 30, top: 8, bottom: 8, containLabel: true },
      xAxis: { type: "value", max: 100, axisLabel: { fontSize: 10, formatter: "{value}%" } },
      yAxis: { type: "category", data: rows.map(p => p.name), axisLabel: { fontSize: 10, width: 120, overflow: "truncate" } },
      series: [{
        type: "bar", data: rows.map(p => Number(p.usagePct.toFixed(1))), barMaxWidth: 14,
        itemStyle: { color: (p: { value: number }) => (p.value >= 85 ? "#f56c6c" : p.value >= 70 ? "#e6a23c" : "#67c23a") },
        label: { show: true, position: "right", fontSize: 10, formatter: (p: { value: number }) => p.value + "%" },
      }],
    };
  }
  if (title === "接口观测趋势" && m.monitor) {
    const pts = m.monitor;
    return {
      tooltip: { trigger: "axis" },
      legend: { bottom: 0, textStyle: { fontSize: 11 } },
      grid: { left: 40, right: 40, top: 12, bottom: 40 },
      xAxis: { type: "category", data: pts.map(p => p.ts.slice(11)), axisLabel: { fontSize: 10 } },
      yAxis: [
        { type: "value", name: "avg-rt(ms)", axisLabel: { fontSize: 10 } },
        { type: "value", name: "total", axisLabel: { fontSize: 10 } },
      ],
      series: [
        { name: "平均耗时(ms)", type: "line", smooth: true, data: pts.map(p => p.avgRtMs), showSymbol: false },
        { name: "调用次数", type: "line", yAxisIndex: 1, smooth: true, areaStyle: { opacity: 0.08 }, data: pts.map(p => p.total), showSymbol: false },
      ],
    };
  }
  return {};
}

function onResize() {
  instances.forEach(c => c.resize());
}

onMounted(() => window.addEventListener("resize", onResize));
onBeforeUnmount(() => {
  window.removeEventListener("resize", onResize);
  instances.forEach(c => c.dispose());
  instances.clear();
});
</script>

<style scoped>
.diag-charts { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
.chart-cell { min-width: 0; }
.chart-title { font-size: 12px; color: var(--el-text-color-secondary); margin-bottom: 2px; }
.chart-box { height: 210px; }
@media (max-width: 1200px) {
  .diag-charts { grid-template-columns: 1fr; }
}
</style>
