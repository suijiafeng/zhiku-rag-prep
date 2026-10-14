import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// 开发代理让 API 调用保持同源，因此服务端无需为管理控制台开启 CORS。
export default defineConfig({
  plugins: [react()],
  server: { proxy: { "/api": "http://127.0.0.1:8080" } },
});
