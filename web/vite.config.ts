import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";
import { fileURLToPath, URL } from "node:url";
import pkg from "./package.json";

export default defineConfig({
  plugins: [vue()],
  define: {
    __APP_VERSION__: JSON.stringify(pkg.version),
  },
  resolve: {
    alias: {
      "@": fileURLToPath(new URL("./src", import.meta.url)),
    },
  },
  server: {
    port: 5173,
    proxy: {
      // 后端地址可用 BQ_API_TARGET 覆盖（默认本机 8080）
      "/api": { target: `http://${process.env.BQ_API_TARGET || "127.0.0.1:8080"}`, changeOrigin: true },
      "/docs/4.3.5": { target: `http://${process.env.BQ_API_TARGET || "127.0.0.1:8080"}`, changeOrigin: true },
    },
  },
});
