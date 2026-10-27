export interface RiskMeta {
  label: string;
  type: "success" | "warning" | "danger";
}

/** 命令风险字典（与后端 manifest / CommandAllowlist 一致） */
export const riskMeta: Record<string, RiskMeta> = {
  low: { label: "只读 · 低风险", type: "success" },
  mid: { label: "增强观测 · 中风险", type: "warning" },
  high: { label: "高危 · 需审批", type: "danger" },
};

/** 高危命令族：前端二次确认 + 后端黑名单双重拦截 */
export const highRiskFamilies = new Set(["ognl", "redefine", "retransform", "mc", "heapdump", "groovy"]);

export function riskOfCommand(command: string): RiskMeta {
  const head = command.trim().split(/\s+/)[0]?.toLowerCase() ?? "";
  if (highRiskFamilies.has(head)) return riskMeta.high;
  if (["watch", "trace", "tt", "monitor", "stack", "line", "profiler", "jfr"].includes(head)) return riskMeta.mid;
  return riskMeta.low;
}
