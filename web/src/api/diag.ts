import type { AxiosResponse } from "axios";
import { http } from "@/api/http";

export interface DiagResultVO {
  success: boolean;
  output: string;
  error: string;
  costMs: number;
}

/** 执行诊断命令（平台唯一执行入口；高危由后端黑名单拦截） */
export function executeDiag(instanceId: string, command: string, source = "人工") {
  return http.post("/diag/execute", { instanceId, command, source }) as unknown as Promise<DiagResultVO>;
}

/** 会话查询 / 关闭（reset + stop 防增强残留） */
export function getSession(instanceId: string) {
  return http.get(`/diag/session/${instanceId}`) as unknown as Promise<Record<string, unknown>>;
}
export function closeSession(sessionId: string) {
  return http.delete(`/diag/session/${sessionId}`) as unknown as Promise<{ closed: boolean }>;
}

/** 实例与应用 */
export interface InstanceVO { instanceId: string; appId: string; host: string; agentVersion: string; online: boolean; localAgent: boolean; baseHealth: number }
export function getInstances(appId?: string) {
  return http.get("/fleet/instances", { params: appId ? { appId } : {} }) as unknown as Promise<InstanceVO[]>;
}

export function unwrap<T>(resp: unknown): T {
  return resp as unknown as T;
}

export type { AxiosResponse };
