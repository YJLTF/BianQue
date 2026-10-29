import { http } from "@/api/http";

export interface TplItem {
  name: string;
  command: string;
}

export interface TplVO {
  id: number;
  name: string;
  kind: "diag" | "checkup" | "rescue";
  scene: string;
  items: TplItem[];
  /** 命令中的 {{占位符}} 键集合（按出现顺序） */
  placeholders: string[];
  bindAppId: string | null;
  builtIn: boolean;
  enabled: boolean;
  createdBy?: string;
}

export interface TplPayload {
  name: string;
  kind: string;
  scene?: string;
  items: TplItem[];
  bindAppId?: string | null;
  enabled?: boolean;
}

/** 诊断台可用模板（仅启用） */
export function getTemplates(kind?: string) {
  return http.get("/templates", { params: kind ? { kind } : {} }) as unknown as Promise<TplVO[]>;
}

/** 管理列表（含停用） */
export function getAllTemplates(kind?: string) {
  return http.get("/templates/all", { params: kind ? { kind } : {} }) as unknown as Promise<TplVO[]>;
}

export function createTemplate(payload: TplPayload) {
  return http.post("/templates", payload) as unknown as Promise<TplVO>;
}

export function updateTemplate(id: number, payload: TplPayload) {
  return http.put(`/templates/${id}`, payload) as unknown as Promise<TplVO>;
}

export function enableTemplate(id: number, enabled: boolean) {
  return http.post(`/templates/${id}/enable`, { enabled }) as unknown as Promise<TplVO>;
}

export function deleteTemplate(id: number) {
  return http.delete(`/templates/${id}`) as unknown as Promise<{ deleted: string }>;
}
