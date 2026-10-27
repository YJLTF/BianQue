import axios from "axios";

/** 平台 API 客户端：开发期经 Vite 代理到 bq-server(8080)。 */
export const http = axios.create({ baseURL: "/api", timeout: 15000 });

http.interceptors.response.use(
  (resp) => resp.data,
  (error) => Promise.reject(error),
);
