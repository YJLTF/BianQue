import type { AxiosResponse } from "axios";
import { http } from "@/api/http";

export interface DiagMetrics {
  kind: "thread" | "memory" | "jvm" | "dashboard" | "monitor";
  thread?: { total: number | null; stateCounts: Record<string, number>; topCpu: { name: string; id: number; cpuPct: number; state: string }[] };
  memory?: { pools: { name: string; usagePct: number; usedBytes?: number }[]; heapPct: number | null; oldPct: number | null; metaPct: number | null; cpuPct: number | null };
  monitor?: { ts: string; total: number; success?: number; fail?: number; avgRtMs: number; failRatePct: number }[];
}

export interface DiagResultVO {
  success: boolean;
  output: string;
  error: string;
  costMs: number;
  /** real=本机真实通道（常驻/一次性），simulated=拟真靶场 */
  channel?: "real" | "simulated";
  /** 结构化指标（thread/memory/jvm/dashboard/monitor 可解析时；null=回退文本视图） */
  metrics?: DiagMetrics | null;
}

/** 执行诊断命令（平台唯一执行入口；高危由后端黑名单拦截）。
 *  超时须覆盖服务端命令超时上限 120s（参数中心可调）+ Agent 通道等待余量 30s，否则 axios 先行中断报
 *  「timeout of 15000ms exceeded」一类的假超时；全局默认 15s 不适用本接口。 */
export function executeDiag(instanceId: string, command: string, source = "人工") {
  return http.post("/diag/execute", { instanceId, command, source },
    { timeout: 150000 }) as unknown as Promise<DiagResultVO>;
}

/** 性能剖析（火焰图）：profiler start / stop（产物 cat 回传落库，返回同源 iframe 地址） */
export function profileStart(instanceId: string) {
  return http.post("/diag/profile/start", { instanceId }, { timeout: 150000 }) as unknown as
    Promise<{ started: boolean; output: string; costMs: number }>;
}

export function profileStop(instanceId: string) {
  return http.post("/diag/profile/stop", { instanceId }, { timeout: 300000 }) as unknown as
    Promise<{ artifactId: number; path: string; sizeBytes: number; url: string }>;
}

/** 签发产物查看票据（一次性/60s）：iframe 无法携带 Bearer 头，blob 载荷会继承页面 CSP 被浏览器拦截 */
export function artifactTicket(id: number) {
  return http.get(`/diag/artifact/${id}/ticket`) as unknown as
    Promise<{ ticket: string; url: string }>;
}

export interface CompareMetric {
  key: string;
  targetMean: number;
  baseMean: number;
  targetSamples: number[];
  baseSamples: number[];
  foldChangeLog2: number;
  pValue: number;
  significant: boolean;
  sufficient: boolean;
}

export interface CompareResultVO {
  rounds: number;
  commands: string[];
  target: string;
  base: string;
  metrics: CompareMetric[];
  insufficient: string[];
}

/** 双实例基线对比（火山图）：同步采样 + Welch t，耗时随轮数增长 */
export function compareDiag(target: string, base: string, rounds: number) {
  return http.post("/diag/compare", { targetInstanceId: target, baseInstanceId: base, rounds },
    { timeout: 300000 }) as unknown as Promise<CompareResultVO>;
}

/** 会话查询 / 关闭（reset + stop 防增强残留） */
export function getSession(instanceId: string) {
  return http.get(`/diag/session/${instanceId}`) as unknown as Promise<Record<string, unknown>>;
}
export function closeSession(sessionId: string) {
  return http.delete(`/diag/session/${sessionId}`) as unknown as Promise<{ closed: boolean }>;
}

/** 实例与应用 */
export interface InstanceVO { instanceId: string; appId: string; host: string; agentVersion: string; online: boolean; localAgent: boolean; baseHealth: number; mainClass?: string }
export function getInstances(appId?: string) {
  return http.get("/fleet/instances", { params: appId ? { appId } : {} }) as unknown as Promise<InstanceVO[]>;
}

/** 本机真实进程纳管（bianque.execution.local-enabled 时开放；免 telnet 的 arthas-boot 通道） */
export interface LocalProcessVO { pid: number; mainClass: string }
export function getLocalProcesses() {
  return http.get("/fleet/local-processes") as unknown as Promise<{ localEnabled: boolean; processes: LocalProcessVO[] }>;
}
/** attach 需启动 arthas-boot 并完成握手，耗时可远超全局 15s */
export function attachLocalProcess(pid: number) {
  return http.post(`/fleet/local-processes/${pid}/attach`,
    null, { timeout: 120000 }) as unknown as Promise<InstanceVO>;
}

export function unwrap<T>(resp: unknown): T {
  return resp as unknown as T;
}

export type { AxiosResponse };
