import axios from "axios";
import { useAuthStore } from "@/stores/auth";

/** 平台 API 客户端：开发期经 Vite 代理到 bq-server(8080)。自动携带登录 token。 */
export const http = axios.create({ baseURL: "/api", timeout: 15000 });

http.interceptors.request.use((config) => {
  const auth = useAuthStore();
  if (auth.user?.token) {
    config.headers.Authorization = `Bearer ${auth.user.token}`;
  }
  return config;
});

http.interceptors.response.use(
  (resp) => resp.data,
  (error) => {
    const status = error?.response?.status;
    if (status === 401) {
      const auth = useAuthStore();
      auth.clear();
      if (!location.pathname.startsWith("/login")) {
        location.href = "/login";
      }
    }
    return Promise.reject(error);
  },
);

/** 从后端错误响应中提取可读信息（403 权限不足 / 400 参数错误等） */
export function errMsg(e: unknown): string {
  const anyE = e as { response?: { data?: { message?: string; error?: string } }; message?: string };
  return anyE?.response?.data?.message || anyE?.response?.data?.error || anyE?.message || "请求失败";
}
