import { http } from "@/api/http";

/** 参数中心（/api/admin/config，仅管理员）：查看/修改/恢复默认，改动入审计 */
export interface SysConfigItem {
  key: string;
  value: string;
  defaultValue: string;
  valueType: string;
  min: number;
  max: number;
  category: string;
  displayName: string;
  description: string;
  /** 生效语义：即时 / 新会话 / 下一心跳 / 下次巡检… */
  effect: string;
  customized: boolean;
  updatedBy?: string;
  updatedAt?: string;
}

export function getConfigs() {
  return http.get("/admin/config") as unknown as Promise<SysConfigItem[]>;
}

export function updateConfig(key: string, value: string) {
  return http.put(`/admin/config/${key}`, { value }) as unknown as Promise<SysConfigItem>;
}

export function resetConfig(key: string) {
  return http.post(`/admin/config/${key}/reset`) as unknown as Promise<SysConfigItem>;
}
