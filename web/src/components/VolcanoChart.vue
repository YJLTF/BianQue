<template>
  <div class="volcano-wrap">
    <div ref="boxRef" class="volcano-box" />
    <div class="volcano-note">
      x = log₂(目标/基线均值) · y = −log₁₀(p)（Welch t 检验）· 虚线为参考线（|log₂FC|=1、p=0.05）·
      红点目标侧显著升高 / 蓝点显著降低 / 灰点不显著 · 样本不足指标见下方表格 · 本图为<b>参考性对比</b>（采样 {{ rounds }} 轮）
    </div>
  </div>
</template>

<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import * as echarts from "echarts/core";
import { ScatterChart } from "echarts/charts";
import { GridComponent, TooltipComponent, LegendComponent, MarkLineComponent } from "echarts/components";
import { CanvasRenderer } from "echarts/renderers";
import type { CompareMetric } from "@/api/diag";

echarts.use([ScatterChart, GridComponent, TooltipComponent, LegendComponent, MarkLineComponent, CanvasRenderer]);

const props = defineProps<{ metrics: CompareMetric[]; rounds: number }>();

const boxRef = ref<HTMLElement>();
let chart: echarts.ECharts | null = null;

const Y_CAP = 16;

function render() {
  const host = boxRef.value;
  if (!host) return;
  if (!chart) chart = echarts.init(host);
  const sufficient = props.metrics.filter(m => m.sufficient);
  const data = sufficient.map(m => ({
    name: m.key,
    value: [m.foldChangeLog2, Math.min(m.pValue <= 0 ? Y_CAP : -Math.log10(Math.max(m.pValue, 1e-16)), Y_CAP)],
    fold: m.foldChangeLog2,
    p: m.pValue,
    sig: m.significant,
    tm: m.targetMean,
    bm: m.baseMean,
  }));
  chart.setOption({
    tooltip: {
      formatter: (p: { data: { name: string; fold: number; p: number; sig: boolean; tm: number; bm: number } }) =>
        `<b>${p.data.name}</b><br/>目标均值 ${p.data.tm} · 基线均值 ${p.data.bm}` +
        `<br/>log₂倍数变化 ${p.data.fold} · p=${p.data.p}${p.data.sig ? "（显著）" : ""}`,
    },
    legend: { show: false },
    grid: { left: 44, right: 20, top: 14, bottom: 30 },
    xAxis: {
      type: "value", name: "log₂ 倍数变化", nameLocation: "middle",
      nameTextStyle: { fontSize: 11 }, axisLabel: { fontSize: 10 },
    },
    yAxis: { type: "value", name: "−log₁₀(p)", nameTextStyle: { fontSize: 11 }, axisLabel: { fontSize: 10 }, max: Y_CAP },
    series: [{
      type: "scatter",
      data,
      symbolSize: (v: number[]) => 8 + Math.min(14, Math.abs(v[0]) * 4),
      itemStyle: {
        color: (p: { data: { sig: boolean; fold: number } }) =>
          p.data.sig ? (p.data.fold > 0 ? "#f56c6c" : "#409eff") : "#c0c4cc",
      },
      label: {
        show: true, position: "top", fontSize: 10,
        formatter: (p: { data: { name: string; sig: boolean } }) => (p.data.sig ? p.data.name : ""),
      },
      markLine: {
        symbol: "none", lineStyle: { type: "dashed", color: "#dcdfe6" }, label: { fontSize: 10 },
        data: [{ xAxis: 1 }, { xAxis: -1 }, { yAxis: -Math.log10(0.05) }],
      },
    }],
  }, true);
}

function onResize() {
  chart?.resize();
}

watch(() => props.metrics, () => nextTick(render), { deep: true });
onMounted(() => {
  window.addEventListener("resize", onResize);
  nextTick(render);
});
onBeforeUnmount(() => {
  window.removeEventListener("resize", onResize);
  chart?.dispose();
  chart = null;
});
</script>

<style scoped>
.volcano-wrap { min-width: 0; }
.volcano-box { height: 300px; }
.volcano-note { font-size: 11.5px; color: var(--el-text-color-secondary); margin-top: 4px; }
.volcano-note b { color: var(--el-color-warning); }
</style>
