import { http } from "@/api/http";

/** 平台 Agent 节点（/api/agents，登录态） */
export interface AgentProcessVO {
  pid: number;
  mainClass: string;
  instanceId: string;
}

export interface AgentNodeVO {
  agentId: string;
  host: string;
  hostname: string;
  os: string;
  javaVersion: string;
  agentVersion: string;
  firstSeen: string;
  lastHeartbeat: string;
  online: boolean;
  processes: AgentProcessVO[];
}

export interface AgentListVO {
  nodes: AgentNodeVO[];
  total: number;
  online: number;
  processes: number;
}

export interface AgentInstallVO {
  serverUrl: string;
  tokenConfigured: boolean;
  token: string | null;
  downloadUrl: string;
  steps: string[];
  sampleCommand: string;
}

export function getAgentNodes() {
  return http.get("/agents") as unknown as Promise<AgentListVO>;
}

export function getAgentInstall() {
  return http.get("/agents/install") as unknown as Promise<AgentInstallVO>;
}

/** 删除节点（仅 admin；仅离线节点可删，服务端强校验） */
export function deleteAgent(agentId: string) {
  return http.delete(`/agents/${agentId}`) as unknown as Promise<{ deleted: string; instancesRemoved: number }>;
}
