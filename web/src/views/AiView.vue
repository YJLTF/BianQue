<template>
  <el-row :gutter="12">
    <el-col :span="13">
      <el-card shadow="never" header="问诊 · Diag Copilot（S3 交付：MCP 网关 + Playbook）">
        <div class="chat">
          <div class="msg u">帮我看看 order-app 在 pod-3 上为什么响应慢？</div>
          <div class="msg a">
            按「慢接口」SOP 采集一线数据（只读白名单）：
            <div class="step">✓ dashboard(order-app/pod-3) → CPU 96%，线程 412</div>
            <div class="step">✓ thread -n 3 → OrderWorker 阻塞在连接池获取</div>
            <div class="step">✓ trace *OrderService placeOrder '#cost&gt;100' → borrowObject 占 68%</div>
            <div class="step">✓ memory → old gen 87%（未见泄漏斜率）</div>
            <div style="margin-top: 8px"><b>结论：</b>下游 inventory 连接池打满导致慢调用；建议扩容池参数。</div>
          </div>
          <div class="msg a danger">
            已拦截：模型尝试调用 <code>ognl '@System@exit(0)'</code> —— 命中高危黑名单，已阻断并写入审计。
          </div>
          <el-input disabled placeholder="S3 交付：对话式排障（Playbook 引擎 + LLM 编排）" style="margin-top: 10px" />
        </div>
      </el-card>
    </el-col>
    <el-col :span="11">
      <el-card shadow="never" header="个人版 · 现在即可用（Arthas 4.3.5 内置 MCP Server）">
        <p class="sub">在本机启动 arthas 后，在 AI 客户端（Claude Desktop / Cherry Studio / Cline）中配置以下端点，即可对话式调用全部 29 个诊断工具：</p>
        <pre class="out">http://127.0.0.1:8563/mcp</pre>
        <el-button size="small" style="margin-top: 8px" @click="copy">复制端点</el-button>
        <el-alert type="warning" :closable="false" style="margin-top: 12px"
                  title="平台版安全：MCP 网关仅转发只读白名单 · 逐步审计 · 高危动作人工确认 · 会话限流" />
      </el-card>
    </el-col>
  </el-row>
</template>

<script setup lang="ts">
import { ElMessage } from "element-plus";

async function copy() {
  try {
    await navigator.clipboard.writeText("http://127.0.0.1:8563/mcp");
    ElMessage.success("已复制");
  } catch {
    ElMessage.warning("复制失败，请手动选择");
  }
}
</script>

<style scoped>
.chat { display: flex; flex-direction: column; gap: 10px; }
.msg { max-width: 88%; border-radius: 10px; padding: 9px 12px; font-size: 13px; }
.msg.u { align-self: flex-end; background: var(--el-color-primary); color: #fff; border-bottom-right-radius: 3px; }
.msg.a { align-self: flex-start; background: #f5f7fa; border: 1px solid #e4e7ed; border-bottom-left-radius: 3px; }
.msg.a.danger { border-color: var(--el-color-danger-light-5); }
.step { font-size: 12px; color: #606266; margin-top: 4px; }
.sub { font-size: 12.5px; color: #606266; }
.out { background: #f5f7fa; border: 1px solid #e4e7ed; border-radius: 6px; padding: 8px 10px;
       font-size: 12.5px; white-space: pre-wrap; margin: 6px 0 0; }
</style>
